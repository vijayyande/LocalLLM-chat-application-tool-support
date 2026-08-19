import javax.swing.*;
import javax.imageio.ImageIO;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.DataFlavor;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LocalLLMSwingAgent.java
 *
 * Single-file Java Swing desktop client for local LLMs exposing an
 * OpenAI-compatible API.
 *
 * Tested design targets:
 *   - LM Studio: http://127.0.0.1:1234/v1
 *   - Ollama:    http://127.0.0.1:11434/v1
 *   - Other local OpenAI-compatible servers.
 *
 * Features:
 *   - Chat with a local LLM.
 *   - Direct file creation/update on the selected workspace.
 *   - FILE blocks in model responses are automatically written to disk.
 *   - Safe path validation prevents writing outside the workspace.
 *   - Read files into the conversation.
 *   - Workspace file browser.
 *   - Model discovery through GET /models.
 *   - Configurable endpoint, model, temperature and max tokens.
 *   - Conversation history.
 *   - Copy/save response.
 *
 * Compile:
 *   javac --release 17 LocalLLMSwingAgent.java
 *
 * Run:
 *   java LocalLLMSwingAgent
 *
 * Example model response format:
 *
 * <<<FILE:src/main/java/com/example/Main.java>>>
 * package com.example;
 * public class Main { ... }
 * <<<END_FILE>>>
 *
 * The application writes the file directly beneath the selected workspace.
 */
public class LocalLLMSwingAgentV6 extends JFrame {

    // ---------- UI ----------
    private final JTextArea chatArea = new JTextArea();
    private final JTextArea inputArea = new JTextArea(5, 80);
    private final JTextField endpointField = new JTextField("http://127.0.0.1:1234/v1");
    private final JTextField modelField = new JTextField("");
    private final JTextField workspaceField = new JTextField(System.getProperty("user.dir"));
    private final JSpinner temperatureSpinner = new JSpinner(new SpinnerNumberModel(0.2, 0.0, 2.0, 0.1));
    private final JSpinner maxTokensSpinner = new JSpinner(new SpinnerNumberModel(8192, 256, 131072, 256));
    private final JLabel statusLabel = new JLabel("Ready");
    // VISIBLE_LLM_OPERATION_BAR
    private final JPanel operationBar = new JPanel(new BorderLayout(10, 0));
    private final JLabel operationLabel = new JLabel("Ready");
    private final JProgressBar operationProgress = new JProgressBar();
    private final JButton operationCancelButton = new JButton("Cancel");
    private javax.swing.Timer operationTimer;
    private long operationStartMillis;

    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel elapsedLabel = new JLabel("");
    private javax.swing.Timer progressTimer;
    private long requestStartMillis;

    private final DefaultListModel<String> fileListModel = new DefaultListModel<>();
    private final DefaultListModel<String> attachmentListModel = new DefaultListModel<>();
    private final JList<String> attachmentList = new JList<>(attachmentListModel);
    private final List<Path> attachedFiles = new ArrayList<>();
    private static final int MAX_CONTEXT_CHARS = 1_500_000;
    private static final int MAX_IMAGE_BYTES = 15_000_000;


    private final JList<String> fileList = new JList<>(fileListModel);
    private final JButton sendButton = new JButton("Send");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton refreshModelsButton = new JButton("Models");
    private final JComboBox<String> modelCombo = new JComboBox<>();

    // ---------- State ----------
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final List<Map<String, String>> messages = new ArrayList<>();
    private volatile boolean busy = false;
    private volatile boolean cancelRequested = false;
    private volatile java.net.http.HttpRequest currentRequest;
    private volatile CompletableFuture<?> currentWorker;

    private static final String SYSTEM_PROMPT = """
            You are a local software engineering agent working inside a user-selected workspace.

            You can chat normally, explain code, debug problems, and generate complete project files.

            IMPORTANT FILE GENERATION PROTOCOL:
            When the user asks you to create, modify, or replace a file, emit the complete desired
            file contents using exactly this format:

            <<<FILE:relative/path/to/file.ext>>>
            complete file content
            <<<END_FILE>>>

            You may emit multiple FILE blocks in one response.

            Rules:
            1. Paths must be relative to the workspace.
            2. Never use an absolute path.
            3. Never use .. to escape the workspace.
            4. Always provide complete file contents unless the user explicitly asks for a patch.
            5. After FILE blocks, briefly summarize what was created/changed.
            6. Do not put Markdown fences around FILE blocks.
            7. For source code, preserve valid syntax and imports.
            8. If a requested change affects several files, generate every affected file.

            The desktop application automatically writes FILE blocks to disk after receiving your response.
            
            ATTACHMENTS:
            The user may attach multiple files. Attached text and supported document contents are
            supplied in the user message. Use the attachment content as authoritative context.
            If an attachment is binary and only metadata is supplied, do not invent its contents.
                        """;

    private static final Pattern FILE_PATTERN = Pattern.compile(
            "<<<FILE\\s*:\\s*(.*?)\\s*>>>\\s*\\R([\\s\\S]*?)\\R\\s*<<<END_FILE\\s*>>>",
            Pattern.MULTILINE);

    public LocalLLMSwingAgentV6() {
        super("Local LLM Swing Agent - Direct File Generation");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1200, 780));
        setLocationRelativeTo(null);
        buildUi();
        appendSystem("Local LLM agent started. Configure the endpoint/model and select a workspace.");
        refreshWorkspace();
        discoverModels(false);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        setContentPane(root);

        // Always-visible LLM operation bar.
        operationBar.setVisible(true);
        operationBar.setPreferredSize(new Dimension(10, 44));
        operationBar.setMinimumSize(new Dimension(10, 44));
        operationBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));

        operationLabel.setText("Ready");
        operationLabel.setFont(operationLabel.getFont().deriveFont(Font.BOLD, 13f));

        operationProgress.setVisible(false);
        operationProgress.setIndeterminate(false);
        operationProgress.setStringPainted(true);
        operationProgress.setPreferredSize(new Dimension(260, 22));

        operationCancelButton.setVisible(false);
        operationCancelButton.setEnabled(false);
        operationCancelButton.setText("CANCEL");
        operationCancelButton.setFocusable(false);
        operationCancelButton.setPreferredSize(new Dimension(90, 28));
        operationCancelButton.addActionListener(e -> cancelCurrentOperation());

        operationBar.add(operationLabel, BorderLayout.WEST);
        operationBar.add(operationProgress, BorderLayout.CENTER);
        operationBar.add(operationCancelButton, BorderLayout.EAST);

        root.add(operationBar, BorderLayout.NORTH);

        // Top configuration panel
        JPanel config = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; config.add(new JLabel("Endpoint:"), g);
        g.gridx = 1; g.weightx = 1; config.add(endpointField, g);
        g.gridx = 2; g.weightx = 0; config.add(new JLabel("Model:"), g);
        g.gridx = 3; g.weightx = 1; config.add(modelCombo, g);
        g.gridx = 4; g.weightx = 0; config.add(refreshModelsButton, g);

        g.gridx = 0; g.gridy = 1; config.add(new JLabel("Workspace:"), g);
        g.gridx = 1; g.gridwidth = 3; g.weightx = 1; config.add(workspaceField, g);
        JButton browseButton = new JButton("Browse...");
        g.gridx = 4; g.gridwidth = 1; g.weightx = 0; config.add(browseButton, g);

        g.gridx = 0; g.gridy = 2; config.add(new JLabel("Temperature:"), g);
        g.gridx = 1; g.weightx = 0; config.add(temperatureSpinner, g);
        g.gridx = 2; config.add(new JLabel("Max tokens:"), g);
        g.gridx = 3; config.add(maxTokensSpinner, g);
        g.gridx = 4;
        JPanel progressPanel = new JPanel(new BorderLayout(6, 0));
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(180, 18));
        elapsedLabel.setVisible(false);
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(elapsedLabel, BorderLayout.EAST);
        progressPanel.add(statusLabel, BorderLayout.SOUTH);
        config.add(progressPanel, g);

        root.add(config, BorderLayout.NORTH);

        // File browser
        fileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileList.setToolTipText("Double-click a file to add its contents to the chat context.");
        JScrollPane fileScroll = new JScrollPane(fileList);
        fileScroll.setPreferredSize(new Dimension(300, 500));

        JPanel filePanel = new JPanel(new BorderLayout(5, 5));
        filePanel.setBorder(BorderFactory.createTitledBorder("Workspace Files"));
        JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshFiles = new JButton("Refresh");
        JButton addFiles = new JButton("Add Selected to Chat");
        JButton openFolder = new JButton("Open Folder");
        fileButtons.add(refreshFiles);
        fileButtons.add(addFiles);
        fileButtons.add(openFolder);
        filePanel.add(fileScroll, BorderLayout.CENTER);
        filePanel.add(fileButtons, BorderLayout.SOUTH);

        // Chat attachments
        JPanel attachmentPanel = new JPanel(new BorderLayout(5, 5));
        attachmentPanel.setBorder(BorderFactory.createTitledBorder("Chat Attachments"));
        attachmentList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        attachmentList.setVisibleRowCount(4);
        attachmentList.setToolTipText("Double-click to preview. You can also drag files here.");
        attachmentList.setDropMode(DropMode.INSERT);
        attachmentList.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    attachPaths(files.stream().map(File::toPath).toList());
                    return true;
                } catch (Exception ex) {
                    appendSystem("Drag/drop error: " + ex.getMessage());
                    return false;
                }
            }
        });
        attachmentList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    previewSelectedAttachment();
                }
            }
        });

        JPanel attachmentButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton attachButton = new JButton("Attach Files...");
        JButton removeAttachmentButton = new JButton("Remove Selected");
        JButton clearAttachmentsButton = new JButton("Clear");
        JButton previewAttachmentButton = new JButton("Preview");
        attachmentButtons.add(attachButton); attachmentButtons.add(removeAttachmentButton); attachmentButtons.add(clearAttachmentsButton);
        attachmentButtons.add(previewAttachmentButton);
        attachmentPanel.add(new JScrollPane(attachmentList), BorderLayout.CENTER);
        attachmentPanel.add(attachmentButtons, BorderLayout.SOUTH);
        attachButton.addActionListener(e -> chooseAttachments());
        removeAttachmentButton.addActionListener(e -> removeSelectedAttachments());
        clearAttachmentsButton.addActionListener(e -> clearAttachments());
        previewAttachmentButton.addActionListener(e -> previewSelectedAttachment());
        JPanel chatWithAttachments = new JPanel(new BorderLayout(5, 5));

        operationBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.border"), 1),
                BorderFactory.createEmptyBorder(7, 8, 7, 8)));

        operationLabel.setFont(operationLabel.getFont().deriveFont(Font.BOLD, 13f));

        operationProgress.setIndeterminate(false);
        operationProgress.setVisible(false);
        operationProgress.setStringPainted(true);
        operationProgress.setString("");
        operationProgress.setPreferredSize(new Dimension(220, 22));

        operationCancelButton.setVisible(false);
        operationCancelButton.setEnabled(false);
        operationCancelButton.setFont(operationCancelButton.getFont().deriveFont(Font.BOLD));
        operationCancelButton.addActionListener(e -> cancelCurrentOperation());

        operationBar.add(operationLabel, BorderLayout.WEST);
        operationBar.add(operationProgress, BorderLayout.CENTER);
        operationBar.add(operationCancelButton, BorderLayout.EAST);



        // Chat
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(BorderFactory.createTitledBorder("Chat"));
        chatWithAttachments.add(chatScroll, BorderLayout.CENTER);
        chatWithAttachments.add(attachmentPanel, BorderLayout.SOUTH);

        JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, filePanel, chatWithAttachments);
        center.setDividerLocation(300);
        root.add(center, BorderLayout.CENTER);

        // Input
        JPanel bottom = new JPanel(new BorderLayout(5, 5));
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        inputArea.setBorder(BorderFactory.createTitledBorder("Message"));
        bottom.add(new JScrollPane(inputArea), BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(0, 1, 4, 4));
        actions.setPreferredSize(new Dimension(130, 120));

        sendButton.addActionListener(e -> sendMessage());
        cancelButton.addActionListener(e -> cancelCurrentOperation());
        cancelButton.setEnabled(false);
        JButton clearButton = new JButton("Clear Chat");
        JButton copyButton = new JButton("Copy Response");
        JButton saveButton = new JButton("Save Response");

        clearButton.addActionListener(e -> clearConversation());
        copyButton.addActionListener(e -> copyChat());
        saveButton.addActionListener(e -> saveChat());

        actions.add(sendButton);
        actions.add(cancelButton);
        actions.add(clearButton);
        actions.add(copyButton);
        actions.add(saveButton);
        bottom.add(actions, BorderLayout.EAST);

        root.add(bottom, BorderLayout.SOUTH);

        // Actions
        browseButton.addActionListener(e -> chooseWorkspace());
        refreshFiles.addActionListener(e -> refreshWorkspace());
        addFiles.addActionListener(e -> addSelectedFilesToChat());
        openFolder.addActionListener(e -> openWorkspaceFolder());
        refreshModelsButton.addActionListener(e -> discoverModels(true));

        modelCombo.addActionListener(e -> {
            Object selected = modelCombo.getSelectedItem();
            if (selected != null && !selected.toString().isBlank()) {
                modelField.setText(selected.toString());
            }
        });

        // Allow Ctrl+Enter to send
        inputArea.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "send");
        inputArea.getActionMap().put("send", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                sendMessage();
            }
        });

        // Double click file
        fileList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    addSelectedFilesToChat();
                }
            }
        });
    }

    // ---------- Chat ----------

    private void sendMessage() {
        if (busy) return;

        String text = inputArea.getText().trim();
        if (text.isEmpty()) return;

        String model = getModel();
        if (model.isBlank()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter/select a model first.",
                    "Model Required",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        Path workspace = getWorkspace();
        if (workspace == null) return;

        inputArea.setText("");
        String messageWithAttachments = buildMessageWithAttachments(text);
        appendUser(messageWithAttachments);
        addMessage("user", messageWithAttachments);
        cancelRequested = false;
        setBusy(true, "Waiting for local LLM response...");
        startProgressIndicator();

        currentWorker = CompletableFuture.runAsync(() -> {
            try {
                String response = callChatCompletion(model);
                SwingUtilities.invokeLater(() -> {
                    if (cancelRequested) {
                        appendSystem("Operation cancelled by user. Partial LLM response was discarded.");
                        clearAttachments();
                        stopProgressIndicator("Cancelled");
                        setBusy(false, "Cancelled");
                        currentWorker = null;
                        return;
                    }

                    int written = writeFileBlocks(response, workspace);
                    if (written > 0) {
                        appendSystem("Wrote/updated " + written + " file(s) in " + workspace);
                        refreshWorkspace();
                    }

                    addMessage("assistant", response);
                    clearAttachments();
                    stopProgressIndicator("Ready");
                    setBusy(false, "Ready");
                    currentWorker = null;
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (cancelRequested || ex instanceof CancellationException
                            || ex.getCause() instanceof CancellationException) {
                        appendSystem("Operation cancelled by user.");
                        clearAttachments();
                        stopProgressIndicator("Cancelled");
                        setBusy(false, "Cancelled");
                    } else {
                        appendSystem("ERROR: " + rootMessage(ex));
                        stopProgressIndicator("Error");
                        setBusy(false, "Error");
                        currentWorker = null;
                    }
                });
            }
        });
    }

    private String callChatCompletion(String model) throws Exception {
        String endpoint = normalizeEndpoint(endpointField.getText());
        String url = endpoint + "/chat/completions";

        List<Map<String, String>> requestMessages = new ArrayList<>();
        requestMessages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        requestMessages.addAll(messages);

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"model\":").append(jsonString(model)).append(",");
        json.append("\"messages\":[");

        for (int i = 0; i < requestMessages.size(); i++) {
            if (i > 0) json.append(",");

            Map<String, String> message = requestMessages.get(i);

            json.append("{")
                    .append("\"role\":").append(jsonString(message.get("role"))).append(",")
                    .append("\"content\":").append(jsonString(message.get("content")))
                    .append("}");
        }

        json.append("],");
        json.append("\"temperature\":")
                .append(((Number) temperatureSpinner.getValue()).doubleValue()).append(",");
        json.append("\"max_tokens\":")
                .append(((Number) maxTokensSpinner.getValue()).intValue()).append(",");
        json.append("\"stream\":true");
        json.append("}");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.toString(), StandardCharsets.UTF_8))
                .build();

        currentRequest = request;

        if (cancelRequested) {
            throw new CancellationException("Operation cancelled.");
        }

        StringBuilder completeResponse = new StringBuilder();
        boolean streamStarted = false;

        // Tell the user immediately that the HTTP request has actually been sent.
        SwingUtilities.invokeLater(() ->
                statusLabel.setText("Connected to local LLM. Waiting for first token..."));

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String error;
                try (InputStream in = response.body()) {
                    error = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new IOException(
                        "LM Studio HTTP " + response.statusCode() + ": " + error);
            }

            // Start the visible assistant output immediately.
            SwingUtilities.invokeAndWait(() -> {
                chatArea.append("\n\nLOCAL LLM\n----------------------------------------\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
                operationLabel.setText("Connected. Receiving first token...");
            });

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    if (cancelRequested || Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("Operation cancelled.");
                    }

                    if (line.isBlank()) {
                        continue;
                    }

                    // LM Studio/OpenAI streaming uses:
                    // data: {"choices":[{"delta":{"content":"hello"}}]}
                    if (line.startsWith("data:")) {
                        streamStarted = true;

                        String data = line.substring(5).trim();

                        if ("[DONE]".equals(data)) {
                            break;
                        }

                        String delta = extractStreamingDelta(data);

                        if (delta != null && !delta.isEmpty()) {
                            completeResponse.append(delta);
                            appendStreamingText(delta);
                        }
                    }
                }
            }

            // Some OpenAI-compatible servers may ignore stream=true.
            // In that case, the entire response is handled above only if SSE was used.
            if (!streamStarted && completeResponse.isEmpty()) {
                throw new IOException(
                        "LM Studio returned no streaming data. "
                        + "Make sure the endpoint is an OpenAI-compatible LM Studio "
                        + "server endpoint such as http://127.0.0.1:1234/v1");
            }

            if (completeResponse.isEmpty()) {
                throw new IOException(
                        "Local LLM connected but returned an empty response.");
            }

            SwingUtilities.invokeLater(() ->
                    operationLabel.setText("LLM response complete."));

            return completeResponse.toString();

        } finally {
            currentRequest = null;
        }
    }

    private String extractStreamingDelta(String json) {
        // OpenAI format:
        // {"choices":[{"delta":{"content":"..."}}]}
        int deltaIndex = json.indexOf("\"delta\"");
        if (deltaIndex >= 0) {
            int objectStart = json.indexOf('{', deltaIndex);
            if (objectStart >= 0) {
                String nested = extractJsonObject(json, objectStart);
                String content = extractJsonString(nested, "\"content\"");
                if (content != null) return content;
            }
        }

        // Some servers return {"choices":[{"message":{"content":"..."}}]}
        int messageIndex = json.indexOf("\"message\"");
        if (messageIndex >= 0) {
            int objectStart = json.indexOf('{', messageIndex);
            if (objectStart >= 0) {
                String nested = extractJsonObject(json, objectStart);
                String content = extractJsonString(nested, "\"content\"");
                if (content != null) return content;
            }
        }

        return null;
    }

    private String extractJsonObject(String json, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;

        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);

            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }

            if (c == '"') {
                quoted = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }

        return json.substring(start);
    }

    private void appendStreamingText(String text) {
        SwingUtilities.invokeLater(() -> {
            if (cancelRequested) return;

            chatArea.append(text);
            chatArea.setCaretPosition(chatArea.getDocument().getLength());

            long elapsed = System.currentTimeMillis() - operationStartMillis;
            operationLabel.setText(
                    "LLM is generating...  " + formatElapsed(elapsed));
        });
    }

    // ---------- File generation ----------

    private int writeFileBlocks(String response, Path workspace) {
        Matcher matcher = FILE_PATTERN.matcher(response);
        int count = 0;

        while (matcher.find()) {
            String relative = matcher.group(1).trim();
            String content = matcher.group(2);

            try {
                Path target = safeResolve(workspace, relative);

                Files.createDirectories(target.getParent());
                Files.writeString(target, content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);

                count++;
            } catch (IOException fileError) {
                appendSystem("FILE WRITE ERROR [" + relative + "]: " + rootMessage(fileError));
            }
        }

        return count;
    }

    private Path safeResolve(Path workspace, String relative) throws IOException {
        if (relative.isBlank()) {
            throw new IOException("LLM returned an empty file path.");
        }

        // Normalize Windows separators.
        relative = relative.replace('\\', '/');

        // Reject absolute paths.
        if (relative.startsWith("/") ||
                relative.matches("^[A-Za-z]:/.*") ||
                relative.startsWith("//")) {
            throw new IOException("Blocked absolute file path: " + relative);
        }

        Path workspaceReal = workspace.toAbsolutePath().normalize();
        Path target = workspaceReal.resolve(relative).normalize();

        if (!target.startsWith(workspaceReal)) {
            throw new IOException("Blocked path escaping workspace: " + relative);
        }

        return target;
    }

    // ---------- Chat attachments ----------

    private void chooseAttachments() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Attach Files");
        chooser.setMultiSelectionEnabled(true);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            attachPaths(Arrays.stream(chooser.getSelectedFiles())
                    .map(File::toPath).toList());
        }
    }

    private void attachPaths(List<Path> paths) {
        for (Path raw : paths) {
            Path p = raw.toAbsolutePath().normalize();
            if (Files.isRegularFile(p) && !attachedFiles.contains(p)) {
                attachedFiles.add(p);
                attachmentListModel.addElement(p.toString());
            }
        }
        statusLabel.setText(attachedFiles.size() + " attachment(s)");
    }

    private void removeSelectedAttachments() {
        for (String value : attachmentList.getSelectedValuesList()) {
            Path p = Paths.get(value).toAbsolutePath().normalize();
            attachedFiles.remove(p);
            attachmentListModel.removeElement(value);
        }
        statusLabel.setText(attachedFiles.size() + " attachment(s)");
    }

    private void clearAttachments() {
        attachedFiles.clear();
        attachmentListModel.clear();
        attachmentList.clearSelection();
        statusLabel.setText("Ready");
    }

    private void previewSelectedAttachment() {
        String selected = attachmentList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select an attachment first.");
            return;
        }

        Path file = Paths.get(selected);
        try {
            String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);

            if (isImage(lower)) {
                BufferedImage image = ImageIO.read(file.toFile());
                if (image == null) throw new IOException("Unsupported image format.");

                JLabel label = new JLabel(new ImageIcon(scaleImage(image, 900, 650)));
                label.setHorizontalAlignment(SwingConstants.CENTER);

                JDialog dialog = new JDialog(this, "Image Preview - " + file.getFileName(), true);
                dialog.add(new JScrollPane(label));
                dialog.setSize(950, 700);
                dialog.setLocationRelativeTo(this);
                dialog.setVisible(true);
            } else {
                JTextArea area = new JTextArea(extractAttachment(file));
                area.setEditable(false);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

                JDialog dialog = new JDialog(this,
                        "Attachment Preview - " + file.getFileName(), true);
                dialog.add(new JScrollPane(area));
                dialog.setSize(900, 650);
                dialog.setLocationRelativeTo(this);
                dialog.setVisible(true);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Could not preview attachment:\n" + e.getMessage(),
                    "Attachment Preview", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Image scaleImage(BufferedImage image, int maxWidth, int maxHeight) {
        double scale = Math.min(1.0,
                Math.min((double) maxWidth / image.getWidth(),
                         (double) maxHeight / image.getHeight()));

        return image.getScaledInstance(
                Math.max(1, (int)(image.getWidth() * scale)),
                Math.max(1, (int)(image.getHeight() * scale)),
                Image.SCALE_SMOOTH);
    }

    private String buildMessageWithAttachments(String userMessage) {
        if (attachedFiles.isEmpty()) return userMessage;

        StringBuilder result = new StringBuilder(userMessage);
        result.append("\n\n===== USER ATTACHED FILES =====\n");

        int remaining = MAX_CONTEXT_CHARS;

        for (Path file : attachedFiles) {
            if (remaining <= 0) {
                result.append("\n[Remaining attachments omitted: context limit reached]\n");
                break;
            }

            try {
                String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);

                if (isImage(lower)) {
                    result.append("\n===== IMAGE: ").append(file).append(" =====\n")
                            .append("Image pixels are sent separately when the local model supports "
                                    + "OpenAI-compatible vision input. Size: ")
                            .append(Files.size(file)).append(" bytes.\n")
                            .append("===== END IMAGE =====\n");
                    continue;
                }

                String content = extractAttachment(file);
                int allowance = Math.min(remaining, 400_000);
                content = truncateAttachment(content, allowance);

                String block = "\n===== FILE: " + file + " =====\n"
                        + content
                        + "\n===== END FILE =====\n";

                result.append(block);
                remaining -= block.length();

            } catch (Exception e) {
                result.append("\n===== FILE: ").append(file)
                        .append(" =====\n[Processing failed: ")
                        .append(e.getMessage()).append("]\n===== END FILE =====\n");
            }
        }

        result.append("\n===== END USER ATTACHED FILES =====\n");
        return result.toString();
    }

    private String truncateAttachment(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;

        int head = Math.max(1, (int)(max * 0.8));
        int tail = Math.max(1, max - head);

        return text.substring(0, head)
                + "\n\n[...attachment truncated to fit local LLM context...]\n\n"
                + text.substring(text.length() - tail);
    }

    private String extractAttachment(Path file) throws IOException {
        if (!Files.exists(file)) return "[File no longer exists]\n";

        long size = Files.size(file);
        if (size > 50_000_000L) {
            return "[File skipped: larger than 50 MB]\n";
        }

        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);

        if (isImage(lower)) {
            return extractImageMetadata(file);
        }

        if (isPdf(lower)) {
            return extractPdfText(file);
        }

        if (isOfficeOpenXml(lower)) {
            return extractOfficeOpenXml(file, lower);
        }

        if (isOdf(lower)) {
            return extractOdf(file);
        }

        if (isArchive(lower)) {
            return extractArchive(file);
        }

        byte[] bytes = Files.readAllBytes(file);

        if (!looksBinary(bytes) || isKnownText(lower)) {
            return limitAttachmentText(new String(bytes, StandardCharsets.UTF_8));
        }

        return extractBinaryMetadata(file, bytes);
    }

    private boolean isKnownText(String lower) {
        return lower.endsWith(".java") || lower.endsWith(".kt")
                || lower.endsWith(".scala") || lower.endsWith(".groovy")
                || lower.endsWith(".c") || lower.endsWith(".h")
                || lower.endsWith(".cpp") || lower.endsWith(".hpp")
                || lower.endsWith(".cc") || lower.endsWith(".cxx")
                || lower.endsWith(".cs") || lower.endsWith(".go")
                || lower.endsWith(".rs") || lower.endsWith(".py")
                || lower.endsWith(".js") || lower.endsWith(".ts")
                || lower.endsWith(".jsx") || lower.endsWith(".tsx")
                || lower.endsWith(".html") || lower.endsWith(".htm")
                || lower.endsWith(".css") || lower.endsWith(".scss")
                || lower.endsWith(".xml") || lower.endsWith(".xsd")
                || lower.endsWith(".json") || lower.endsWith(".yaml")
                || lower.endsWith(".yml") || lower.endsWith(".properties")
                || lower.endsWith(".ini") || lower.endsWith(".cfg")
                || lower.endsWith(".conf") || lower.endsWith(".sql")
                || lower.endsWith(".sh") || lower.endsWith(".bat")
                || lower.endsWith(".ps1") || lower.endsWith(".md")
                || lower.endsWith(".rst") || lower.endsWith(".log")
                || lower.endsWith(".csv") || lower.endsWith(".tsv")
                || lower.endsWith(".txt") || lower.endsWith(".text")
                || lower.endsWith(".tex") || lower.endsWith(".gradle")
                || lower.endsWith(".pom") || lower.endsWith(".gitignore");
    }

    private boolean isImage(String lower) {
        return lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                || lower.endsWith(".bmp") || lower.endsWith(".webp")
                || lower.endsWith(".svg") || lower.endsWith(".ico")
                || lower.endsWith(".tif") || lower.endsWith(".tiff");
    }

    private boolean isPdf(String lower) {
        return lower.endsWith(".pdf");
    }

    private boolean isOfficeOpenXml(String lower) {
        return lower.endsWith(".docx") || lower.endsWith(".xlsx")
                || lower.endsWith(".pptx");
    }

    private boolean isOdf(String lower) {
        return lower.endsWith(".odt") || lower.endsWith(".ods")
                || lower.endsWith(".odp");
    }

    private boolean isArchive(String lower) {
        return lower.endsWith(".zip") || lower.endsWith(".jar")
                || lower.endsWith(".war") || lower.endsWith(".ear")
                || lower.endsWith(".7z") || lower.endsWith(".tar")
                || lower.endsWith(".gz");
    }

    private String extractOfficeOpenXml(Path file, String lower) throws IOException {
        StringBuilder out = new StringBuilder();

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            int count = 0;

            while ((entry = zis.getNextEntry()) != null && count < 1000) {
                if (entry.isDirectory()) continue;

                String n = entry.getName();

                // Word document text.
                if (lower.endsWith(".docx") &&
                        (n.equals("word/document.xml")
                                || n.startsWith("word/header")
                                || n.startsWith("word/footer")
                                || n.startsWith("word/footnotes"))) {
                    String xml = readZipEntry(zis);
                    out.append(xmlToReadableText(xml)).append("\n");
                    count++;
                }

                // Excel cells.
                else if (lower.endsWith(".xlsx") &&
                        (n.startsWith("xl/worksheets/")
                                || n.equals("xl/sharedStrings.xml"))) {
                    String xml = readZipEntry(zis);
                    out.append("\n--- ").append(n).append(" ---\n")
                            .append(xmlToReadableText(xml)).append("\n");
                    count++;
                }

                // PowerPoint slide/notes text.
                else if (lower.endsWith(".pptx") &&
                        (n.startsWith("ppt/slides/")
                                || n.startsWith("ppt/notesSlides/"))) {
                    String xml = readZipEntry(zis);
                    out.append("\n--- ").append(n).append(" ---\n")
                            .append(xmlToReadableText(xml)).append("\n");
                    count++;
                }
            }
        }

        String text = out.toString().trim();
        return text.isEmpty()
                ? "[Office document contained no extractable text]\n"
                : limitAttachmentText(text);
    }

    private String extractOdf(Path file) throws IOException {
        StringBuilder out = new StringBuilder();

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("content.xml".equals(entry.getName())) {
                    out.append(xmlToReadableText(readZipEntry(zis)));
                    break;
                }
            }
        }

        return limitAttachmentText(out.toString());
    }

    private String extractArchive(Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("Archive entries:\n");

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            int count = 0;

            while ((entry = zis.getNextEntry()) != null && count < 1000) {
                out.append(" - ").append(entry.getName());

                if (!entry.isDirectory() && entry.getSize() >= 0) {
                    out.append(" (").append(entry.getSize()).append(" bytes)");
                }

                out.append("\n");
                count++;
            }
        }

        out.append("\nOnly archive metadata is attached automatically. "
                + "Use the extracted files or attach individual files when their content is needed.\n");

        return limitAttachmentText(out.toString());
    }

    private String extractPdfText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);

        StringBuilder out = new StringBuilder();

        // Best-effort extraction of simple PDF literal strings.
        Pattern p = Pattern.compile("\\(([^\\)]{1,10000})\\)");
        Matcher m = p.matcher(raw);

        while (m.find() && out.length() < 1_000_000) {
            String value = m.group(1)
                    .replace("\\(", "(")
                    .replace("\\)", ")")
                    .replace("\\n", "\n")
                    .replace("\\r", "\n")
                    .replace("\\t", "\t");

            if (containsUsefulPdfText(value)) {
                out.append(value).append('\n');
            }
        }

        if (out.isEmpty()) {
            return "[PDF attached. Basic Java-only extraction found no readable text. "
                    + "For scanned PDFs or complex PDFs, OCR/PDF libraries are required.]\n";
        }

        return limitAttachmentText(out.toString());
    }

    private boolean containsUsefulPdfText(String s) {
        int printable = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c)
                    || ".,;:!?-_()[]{}@#$%&'\"/".indexOf(c) >= 0) {
                printable++;
            }
        }
        return s.length() > 2 && printable >= s.length() * 0.75;
    }

    private String extractImageMetadata(Path file) throws IOException {
        String type = Files.probeContentType(file);
        if (type == null) type = "image/unknown";

        return "Image attachment: " + file.getFileName()
                + "\nMIME type: " + type
                + "\nSize: " + Files.size(file) + " bytes\n"
                + "[Image pixels are not sent by this text-only API client. "
                + "A vision-capable request format can be added for local multimodal models.]\n";
    }

    private String extractBinaryMetadata(Path file, byte[] bytes) throws IOException {
        String type = Files.probeContentType(file);
        if (type == null) type = "application/octet-stream";

        return "Binary attachment: " + file.getFileName()
                + "\nMIME type: " + type
                + "\nSize: " + bytes.length + " bytes\n"
                + "[Binary content omitted from text-only LLM context.]\n";
    }

    private String readZipEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        long total = 0;

        while ((n = zis.read(buffer)) != -1) {
            total += n;
            if (total > 5_000_000) break;
            out.write(buffer, 0, n);
        }

        return out.toString(StandardCharsets.UTF_8);
    }

    private String xmlToReadableText(String xml) {
        // Remove XML declarations/comments/styles.
        String s = xml.replaceAll("(?s)<!--.*?-->", " ");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");

        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n")
                .trim();
    }

    private String limitAttachmentText(String text) {
        final int maxChars = 2_000_000;
        if (text.length() <= maxChars) return text;

        return text.substring(0, maxChars)
                + "\n\n[Attachment content truncated at 2,000,000 characters]\n";
    }

    // ---------- Workspace ----------

    private Path getWorkspace() {
        try {
            Path p = Paths.get(workspaceField.getText().trim()).toAbsolutePath().normalize();
            if (!Files.exists(p)) Files.createDirectories(p);
            if (!Files.isDirectory(p)) throw new IOException("Not a directory: " + p);
            return p;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Invalid workspace:\n" + e.getMessage(),
                    "Workspace Error",
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private void chooseWorkspace() {
        JFileChooser chooser = new JFileChooser(workspaceField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            workspaceField.setText(chooser.getSelectedFile().getAbsolutePath());
            refreshWorkspace();
        }
    }

    private void refreshWorkspace() {
        Path workspace;
        try {
            workspace = Paths.get(workspaceField.getText().trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(workspace)) return;
        } catch (Exception e) {
            return;
        }

        fileListModel.clear();

        try {
            Files.walk(workspace)
                    .filter(Files::isRegularFile)
                    .filter(p -> !isHiddenBuildDirectory(workspace.relativize(p)))
                    .sorted()
                    .limit(5000)
                    .forEach(p -> fileListModel.addElement(
                            workspace.relativize(p).toString()));
        } catch (IOException e) {
            appendSystem("Could not scan workspace: " + e.getMessage());
        }
    }

    private boolean isHiddenBuildDirectory(Path relative) {
        for (Path p : relative) {
            String s = p.toString().toLowerCase(Locale.ROOT);
            if (s.equals(".git") || s.equals(".idea") || s.equals("target")
                    || s.equals("build") || s.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    private void addSelectedFilesToChat() {
        Path workspace = getWorkspace();
        if (workspace == null) return;

        List<String> selected = fileList.getSelectedValuesList();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select one or more files first.");
            return;
        }

        StringBuilder context = new StringBuilder();
        context.append("Here are the selected workspace files. Use them as context.\n\n");

        for (String rel : selected) {
            try {
                Path file = safeResolve(workspace, rel);
                long size = Files.size(file);

                // Avoid accidentally loading huge/binary files.
                if (size > 2_000_000) {
                    context.append("===== ").append(rel)
                            .append(" (skipped: >2 MB) =====\n\n");
                    continue;
                }

                byte[] bytes = Files.readAllBytes(file);
                if (looksBinary(bytes)) {
                    context.append("===== ").append(rel)
                            .append(" (binary file, content omitted) =====\n\n");
                    continue;
                }

                String content = new String(bytes, StandardCharsets.UTF_8);
                context.append("===== ").append(rel).append(" =====\n")
                        .append(content)
                        .append("\n===== END ").append(rel).append(" =====\n\n");
            } catch (Exception e) {
                context.append("===== ").append(rel)
                        .append(" ERROR: ").append(e.getMessage())
                        .append(" =====\n\n");
            }
        }

        inputArea.setText(context.toString());
        inputArea.requestFocus();
    }

    private boolean looksBinary(byte[] data) {
        int sample = Math.min(data.length, 8192);
        int suspicious = 0;
        for (int i = 0; i < sample; i++) {
            int b = data[i] & 0xff;
            if (b == 0) return true;
            if (b < 7 || (b > 14 && b < 32)) suspicious++;
        }
        return suspicious > sample / 20;
    }

    private void openWorkspaceFolder() {
        Path workspace = getWorkspace();
        if (workspace == null) return;
        try {
            Desktop.getDesktop().open(workspace.toFile());
        } catch (Exception e) {
            appendSystem("Could not open folder: " + e.getMessage());
        }
    }

    // ---------- Models ----------

    private void discoverModels(boolean showErrors) {
        String endpoint = normalizeEndpoint(endpointField.getText());

        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/models"))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
                }

                List<String> ids = extractModelIds(response.body());

                SwingUtilities.invokeLater(() -> {
                    String current = modelField.getText().trim();
                    modelCombo.removeAllItems();

                    for (String id : ids) modelCombo.addItem(id);

                    if (!current.isBlank()) {
                        modelCombo.setSelectedItem(current);
                        if (modelCombo.getSelectedItem() == null) {
                            modelCombo.addItem(current);
                            modelCombo.setSelectedItem(current);
                        }
                    } else if (!ids.isEmpty()) {
                        modelCombo.setSelectedIndex(0);
                        modelField.setText(ids.get(0));
                    }

                    statusLabel.setText("Models: " + ids.size());
                });
            } catch (Exception e) {
                if (showErrors) {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this,
                                    "Model discovery failed:\n" + rootMessage(e),
                                    "LLM Connection",
                                    JOptionPane.ERROR_MESSAGE));
                }
            }
        });
    }

    private List<String> extractModelIds(String json) {
        List<String> result = new ArrayList<>();
        Pattern p = Pattern.compile("\"id\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(json);
        while (m.find()) {
            String id = unescapeJson(m.group(1));
            if (!result.contains(id)) result.add(id);
        }
        return result;
    }

    // ---------- UI helpers ----------

    private void appendUser(String text) {
        chatArea.append("\n\nYOU\n----------------------------------------\n");
        chatArea.append(text);
        chatArea.append("\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private void appendAssistant(String text) {
        chatArea.append("\n\nLOCAL LLM\n----------------------------------------\n");
        chatArea.append(text);
        chatArea.append("\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private void appendSystem(String text) {
        chatArea.append("\n\nSYSTEM\n----------------------------------------\n");
        chatArea.append(text);
        chatArea.append("\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private void addMessage(String role, String content) {
        messages.add(new HashMap<>(Map.of("role", role, "content", content)));

        // Keep the request reasonably sized.
        // System prompt is always added separately.
        while (messages.size() > 30) {
            messages.remove(0);
        }
    }

    private void clearConversation() {
        messages.clear();
        chatArea.setText("");
        appendSystem("Conversation cleared.");
    }

    private void copyChat() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(chatArea.getText()), null);
            statusLabel.setText("Copied");
        } catch (Exception e) {
            statusLabel.setText("Copy failed");
        }
    }

    private void saveChat() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Chat");
        chooser.setSelectedFile(new File("local-llm-chat.txt"));

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.writeString(chooser.getSelectedFile().toPath(),
                        chatArea.getText(), StandardCharsets.UTF_8);
                statusLabel.setText("Chat saved");
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                        e.getMessage(), "Save Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void startProgressIndicator() {
        requestStartMillis = System.currentTimeMillis();
        operationStartMillis = requestStartMillis;

        operationLabel.setText("LLM is generating...  0s");
        operationProgress.setVisible(true);
        operationProgress.setIndeterminate(true);
        operationProgress.setString("Working...");
        operationCancelButton.setVisible(true);
        operationCancelButton.setEnabled(true);

        if (operationTimer != null) operationTimer.stop();
        operationTimer = new javax.swing.Timer(250, e -> {
            long elapsed = System.currentTimeMillis() - operationStartMillis;
            operationLabel.setText("LLM is generating...  " + formatElapsed(elapsed));
        });
        operationTimer.start();

        operationBar.revalidate();
        operationBar.repaint();
        getContentPane().revalidate();
        getContentPane().repaint();
    }

    private void stopProgressIndicator(String finalStatus) {
        if (operationTimer != null) {
            operationTimer.stop();
            operationTimer = null;
        }

        operationLabel.setText(finalStatus);
        operationProgress.setIndeterminate(false);
        operationProgress.setVisible(false);
        operationProgress.setString("");
        operationCancelButton.setEnabled(false);
        operationCancelButton.setVisible(false);

        operationBar.revalidate();
        operationBar.repaint();
        getContentPane().revalidate();
        getContentPane().repaint();

        if (progressTimer != null) {
            progressTimer.stop();
            progressTimer = null;
        }
        progressBar.setVisible(false);
        elapsedLabel.setVisible(false);
    }

    private String formatElapsed(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }

        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return minutes + "m " + remainingSeconds + "s";
    }

    private void cancelCurrentOperation() {
        if (!busy) return;

        cancelRequested = true;
        operationLabel.setText("Cancelling...");
        operationCancelButton.setEnabled(false);
        operationProgress.setIndeterminate(true);

        // Interrupt the thread blocked in HttpClient.send().
        // HttpClient observes interruption and terminates the request.
        Thread.currentThread(); // keep this handler on EDT; actual worker is interrupted below.

        statusLabel.setText("Cancelling...");
        operationLabel.setText("Cancelling...");
        operationCancelButton.setEnabled(false);
        operationProgress.setIndeterminate(true);
        operationProgress.setString("Cancelling...");
        cancelButton.setEnabled(false);

        // Closing the HTTP connection is performed by cancelling the worker future.
        // The worker future is stored by startOperation().
        if (currentWorker != null) {
            currentWorker.cancel(true);
        }
    }

    private void setBusy(boolean value, String status) {
        busy = value;
        sendButton.setEnabled(!value);
        refreshModelsButton.setEnabled(!value);
        if (cancelButton != null) {
            cancelButton.setEnabled(value);
        }
        statusLabel.setText(status);
        inputArea.setEnabled(!value);
        if (value) {
            inputArea.setToolTipText("Please wait for the local LLM response.");
        } else {
            inputArea.setToolTipText(null);
        }
    }

    private String getModel() {
        String combo = Objects.toString(modelCombo.getSelectedItem(), "").trim();
        String field = modelField.getText().trim();
        return !combo.isBlank() ? combo : field;
    }

    private String buildMessageJson(Map<String, String> message) {
        String role = message.get("role");
        String content = message.get("content");

        if ("user".equals(role) && !attachedFiles.isEmpty()) {
            List<Path> images = attachedFiles.stream()
                    .filter(p -> isImage(p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();

            if (!images.isEmpty()) {
                StringBuilder array = new StringBuilder("[");
                array.append("{\"type\":\"text\",\"text\":")
                        .append(jsonString(content)).append("}");

                for (Path image : images) {
                    try {
                        byte[] data = Files.readAllBytes(image);
                        if (data.length > MAX_IMAGE_BYTES) continue;

                        String mime = Files.probeContentType(image);
                        if (mime == null) mime = guessImageMime(image);

                        array.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":")
                                .append(jsonString("data:" + mime + ";base64,"
                                        + Base64.getEncoder().encodeToString(data)))
                                .append("}}");
                    } catch (Exception ignored) {
                    }
                }

                array.append("]");
                return "{\"role\":" + jsonString(role)
                        + ",\"content\":" + array + "}";
            }
        }

        return "{\"role\":" + jsonString(role)
                + ",\"content\":" + jsonString(content) + "}";
    }

    private String guessImageMime(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "image/jpeg";
    }

    private static String normalizeEndpoint(String endpoint) {
        String s = endpoint == null ? "" : endpoint.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);

        if (s.endsWith("/chat/completions")) {
            s = s.substring(0, s.length() - "/chat/completions".length());
        }
        return s;
    }

    private static String rootMessage(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null) x = x.getCause();
        return x.getMessage() == null ? x.toString() : x.getMessage();
    }

    // ---------- Minimal JSON helpers ----------
    // Avoids third-party dependencies so this remains one Java file.

    private static String jsonString(String s) {
        if (s == null) return "null";

        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) {
                        out.append(String.format("\\u%04x", (int)c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /**
     * Extracts a JSON string value for the first occurrence of a property.
     * Example: "content":"hello\\nworld"
     */
    private static String extractJsonString(String json, String property) {
        int start = json.indexOf(property);
        if (start < 0) return null;

        int colon = json.indexOf(':', start + property.length());
        if (colon < 0) return null;

        int quote = colon + 1;
        while (quote < json.length() && Character.isWhitespace(json.charAt(quote))) quote++;
        if (quote >= json.length() || json.charAt(quote) != '"') return null;

        StringBuilder value = new StringBuilder();

        for (int i = quote + 1; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"') return value.toString();

            if (c == '\\' && i + 1 < json.length()) {
                char e = json.charAt(++i);
                switch (e) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            String hex = json.substring(i + 1, i + 5);
                            try {
                                value.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException ex) {
                                value.append("\\u").append(hex);
                                i += 4;
                            }
                        }
                    }
                    default -> value.append(e);
                }
            } else {
                value.append(c);
            }
        }
        return null;
    }

    private static String unescapeJson(String s) {
        String result = extractJsonString("\"x\":\"" + s + "\"", "\"x\"");
        return result == null ? s : result;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }

            LocalLLMSwingAgentV6 app = new LocalLLMSwingAgentV6();
            app.setVisible(true);
        });
    }
}
