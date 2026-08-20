import javax.swing.*;
import javax.imageio.ImageIO;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.DataFlavor;
import java.io.*;
import java.net.URI;
import java.net.HttpURLConnection;
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
public class LocalLLMSwingAgentV19 extends JFrame {

    private static final String APP_VERSION = "19.0";

    // ---------- UI ----------
    private final JEditorPane chatArea = new JEditorPane();
    private final Set<String> expandedChatFiles =
            Collections.synchronizedSet(new HashSet<>());
    private final StringBuilder streamingChatText = new StringBuilder();

    private final JTextArea inputArea = new JTextArea(5, 80);
    private final JTextField endpointField = new JTextField("http://127.0.0.1:1234/v1");
    private final JTextField modelField = new JTextField("");
    // Prevent model discovery / combo refresh from overwriting manual typing.
    private volatile boolean syncingModelCombo = false;
    private final JTextField workspaceField = new JTextField(System.getProperty("user.dir"));
    private final JSpinner temperatureSpinner = new JSpinner(new SpinnerNumberModel(0.2, 0.0, 2.0, 0.1));
    private final JTextField maxTokensField = new JTextField("8192", 8);
    private final JLabel contextLabel = new JLabel("Context: detecting...");
    private final JLabel statusLabel = new JLabel("Ready");
    // Application version is also available in the UI tooltip.

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
    private volatile int modelContextTokens = 8192;
    private volatile String detectedContextModel = "";
    private final StringBuilder streamedFileBuffer = new StringBuilder();
    private final Set<String> streamedWrittenFiles =
            Collections.synchronizedSet(new LinkedHashSet<>());

    /*
     * The parser intentionally accepts small variations because local models
     * are not guaranteed to reproduce whitespace exactly.
     *
     * Supported:
     *   <<<FILE:path>>>
     *   <<<FILE: path >>>
     *   <<<FILE path>>>
     * and the corresponding <<<END_FILE>>> marker.
     */
    private static final Pattern FILE_BLOCK_PATTERN = Pattern.compile(
            "(?is)<<<\\s*FILE\\s*:?\\s*(.*?)\\s*>>>\\s*(.*?)\\s*<<<\\s*END[_ ]?FILE\\s*>>>"
    );





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

    // Persistent local session state. This is intentionally invisible in the UI.
    // The session file contains only relative paths of files generated by this agent.
    // ---------- Persistent application configuration ----------
    // Configuration is kept outside the workspace so it survives workspace
    // changes and application restarts. Generated files remain workspace-local.
    private static final String CONFIG_FILE_NAME = ".localllm-swing-agent.properties";

    /**
     * Configuration is stored beside the launched JAR/executable, not under
     * the user's home directory.
     *
     * For a JAR launched as:
     *   C:\Download\LocalLLM\LocalLLMSwingAgentV19.jar
     *
     * the configuration will be:
     *   C:\Download\LocalLLM\.localllm-swing-agent.properties
     *
     * When running directly from the IDE/classes, the current working
     * directory is used as the fallback.
     */
    private final Path configFile = resolveApplicationDirectory()
            .resolve(CONFIG_FILE_NAME);

    private static final String SESSION_FILE_NAME = ".localllm-session";
    private final Set<String> sessionGeneratedFiles =
            Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile Path loadedSessionWorkspace;

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
            Every generated/updated file is registered in the persistent workspace session and is
            automatically supplied to the LLM on the next chat message. The user does not need
            to attach previously generated files again.
            
            ATTACHMENTS:
            The user may attach multiple files. Attached text and supported document contents are
            supplied in the user message. Use the attachment content as authoritative context.
            If an attachment is binary and only metadata is supplied, do not invent its contents.
                        """;

    private static final Pattern FILE_PATTERN = Pattern.compile(
            "<<<FILE\\s*:\\s*(.*?)\\s*>>>\\s*\\R([\\s\\S]*?)\\R\\s*<<<END_FILE\\s*>>>",
            Pattern.MULTILINE);

    public LocalLLMSwingAgentV19() {
        super("Local LLM Swing Agent v19.0");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1200, 780));
        setLocationRelativeTo(null);

        // Restore the complete user configuration before constructing the UI.
        // This prevents the UI defaults from briefly replacing persisted values.
        loadConfiguration();

        buildUi();
        installConfigurationPersistence();
        saveConfiguration();

        appendSystem("Local LLM agent v" + APP_VERSION + " started. Configure the endpoint/model and select a workspace.");
        refreshWorkspace();
        discoverModels(false);
    

        // Load the hidden persistent session for the selected workspace.
        loadSessionForWorkspace(getWorkspace());
}


    private Path resolveApplicationDirectory() {
        try {
            // When running from a JAR, CodeSource points to the actual JAR.
            URI location = LocalLLMSwingAgentV19.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI();

            Path path = Paths.get(location);

            if (Files.isRegularFile(path)) {
                Path parent = path.getParent();
                if (parent != null) {
                    return parent.toAbsolutePath().normalize();
                }
            }

            if (Files.isDirectory(path)) {
                return path.toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
        }

        // Fallback for unusual launchers / IDEs.
        return Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
    }

    private void loadConfiguration() {
        if (!Files.isRegularFile(configFile)) return;

        Properties props = new Properties();

        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException ignored) {
            return;
        }

        String endpoint = props.getProperty("endpoint");
        String model = props.getProperty("model");
        String workspace = props.getProperty("workspace");
        String temperature = props.getProperty("temperature");
        String maxTokens = props.getProperty("maxTokens");

        if (endpoint != null && !endpoint.isBlank())
            endpointField.setText(endpoint);

        if (model != null)
            modelField.setText(model);

        if (workspace != null && !workspace.isBlank())
            workspaceField.setText(workspace);

        if (temperature != null) {
            try {
                double value = Double.parseDouble(temperature);
                value = Math.max(0.0, Math.min(2.0, value));
                temperatureSpinner.setValue(value);
            } catch (NumberFormatException ignored) {
            }
        }

        if (maxTokens != null && !maxTokens.isBlank())
            maxTokensField.setText(maxTokens);

        // Window position/size are also persisted when available.
        try {
            int x = Integer.parseInt(props.getProperty("window.x", "0"));
            int y = Integer.parseInt(props.getProperty("window.y", "0"));
            int w = Integer.parseInt(props.getProperty("window.width", "1200"));
            int h = Integer.parseInt(props.getProperty("window.height", "780"));

            if (w >= 900 && h >= 600) {
                setSize(w, h);
                if (x >= -10000 && y >= -10000) {
                    setLocation(x, y);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void saveConfiguration() {
        Properties props = new Properties();

        props.setProperty("endpoint", endpointField.getText().trim());
        props.setProperty("model", modelField.getText().trim());
        props.setProperty("workspace", workspaceField.getText().trim());
        props.setProperty("temperature",
                String.valueOf(((Number) temperatureSpinner.getValue()).doubleValue()));
        props.setProperty("maxTokens", maxTokensField.getText().trim());

        Point location = getLocation();
        Dimension size = getSize();

        props.setProperty("window.x", Integer.toString(location.x));
        props.setProperty("window.y", Integer.toString(location.y));
        props.setProperty("window.width", Integer.toString(size.width));
        props.setProperty("window.height", Integer.toString(size.height));

        try {
            Files.createDirectories(configFile.getParent());
            try (OutputStream out = Files.newOutputStream(
                    configFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {

                props.store(out,
                        "Local LLM Swing Agent persistent configuration");
            }
        } catch (IOException ignored) {
            // Configuration persistence must never break normal operation.
        }
    }

    private void installConfigurationPersistence() {
        endpointField.addActionListener(e -> saveConfiguration());
        modelField.addActionListener(e -> saveConfiguration());
        maxTokensField.addActionListener(e -> {
            normalizeMaxTokensField();
            saveConfiguration();
        });

        endpointField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                saveConfiguration();
            }
        });

        modelField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                saveConfiguration();
            }
        });

        workspaceField.addActionListener(e -> {
            saveConfiguration();
            refreshWorkspace();
            loadSessionForWorkspace(getWorkspaceForWorker());
        });

        workspaceField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                saveConfiguration();
            }
        });

        maxTokensField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                normalizeMaxTokensField();
                saveConfiguration();
            }
        });

        temperatureSpinner.addChangeListener(e -> saveConfiguration());

        // Model combo selection is a user configuration change.
        modelCombo.addActionListener(e -> {
            if (!syncingModelCombo && modelCombo.hasFocus()) {
                saveConfiguration();
            }
        });

        // Save one final snapshot whenever the window is closing.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                saveConfiguration();

                Path workspace = getWorkspaceForWorker();
                if (workspace != null) {
                    saveSessionManifest(workspace);
                }
            }
        });
    }

    // ---------- Font Icons ----------
    // Uses the Windows Segoe Fluent Icons / Segoe MDL2 Assets font.
    // The font is installed on current Windows versions. If unavailable,
    // the icon gracefully falls back to a small Unicode glyph.
    private static final class FontIcon implements Icon {
        private final String glyph;
        private final int size;
        private final Color color;

        FontIcon(String glyph, int size, Color color) {
            this.glyph = glyph;
            this.size = size;
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return size + 2;
        }

        @Override
        public int getIconHeight() {
            return size + 2;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                Font font = new Font(
                        "Segoe Fluent Icons",
                        Font.PLAIN,
                        size);

                if (font.getFamily().equals(Font.DIALOG)) {
                    font = new Font(
                            "Segoe MDL2 Assets",
                            Font.PLAIN,
                            size);
                }

                g2.setFont(font);
                g2.setColor(color != null
                        ? color
                        : c != null ? c.getForeground() : Color.DARK_GRAY);

                FontMetrics fm = g2.getFontMetrics();

                int baseline = y
                        + (getIconHeight() - fm.getHeight()) / 2
                        + fm.getAscent();

                g2.drawString(glyph, x, baseline);
            } finally {
                g2.dispose();
            }
        }
    }

    private static String iconGlyph(String text) {
        if (text == null) return "\uE946";

        String t = text.toLowerCase(Locale.ROOT).trim();

        if (t.startsWith("send")) return "\uE724";
        if (t.startsWith("cancel")) return "\uE711";
        if (t.startsWith("refresh") || t.startsWith("models")) return "\uE72C";
        if (t.startsWith("browse")) return "\uE8B7";
        if (t.startsWith("open folder")) return "\uE8B7";
        if (t.startsWith("attach")) return "\uE723";
        if (t.startsWith("remove") || t.startsWith("delete")) return "\uE74D";
        if (t.equals("clear") || t.startsWith("clear ")) return "\uE894";
        if (t.startsWith("preview")) return "\uE7B3";
        if (t.startsWith("add")) return "\uE710";
        if (t.startsWith("copy")) return "\uE8C8";
        if (t.startsWith("save")) return "\uE74E";
        if (t.startsWith("open")) return "\uE8E5";

        if (t.startsWith("endpoint")) return "\uE774";
        if (t.startsWith("model")) return "\uE70C";
        if (t.startsWith("workspace")) return "\uE8B7";
        if (t.startsWith("temperature")) return "\uE9CA";
        if (t.startsWith("max tokens")) return "\uE945";
        if (t.startsWith("context")) return "\uE946";
        if (t.startsWith("files")) return "\uE8A5";
        if (t.startsWith("attachments")) return "\uE723";
        if (t.startsWith("ready")) return "\uE73E";
        if (t.startsWith("llm")) return "\uE70F";
        if (t.startsWith("system")) return "\uE946";
        if (t.startsWith("you")) return "\uE77B";

        return "\uE946";
    }

    private FontIcon makeFontIcon(String text, int size, Color color) {
        return new FontIcon(iconGlyph(text), size, color);
    }

    private void applyFontIconsToComponentTree(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button) {
                button.setIcon(makeFontIcon(button.getText(), 16, null));
                button.setIconTextGap(7);
                button.setHorizontalTextPosition(SwingConstants.RIGHT);
                button.setVerticalTextPosition(SwingConstants.CENTER);
            } else if (component instanceof JLabel label) {
                String text = label.getText();

                if (text != null && !text.isBlank()) {
                    label.setIcon(makeFontIcon(text, 15, null));
                    label.setIconTextGap(6);
                }
            }

            if (component instanceof Container container) {
                applyFontIconsToComponentTree(container);
            }
        }
    }


    // ---------- Generic Local LLM context detection ----------
    private static final class ContextInfo {
        final String provider, model, source;
        final long activeContext, maximumContext;
        final boolean reliable;

        ContextInfo(String provider, String model, long activeContext,
                    long maximumContext, String source, boolean reliable) {
            this.provider=provider; this.model=model;
            this.activeContext=activeContext;
            this.maximumContext=maximumContext;
            this.source=source; this.reliable=reliable;
        }

        long usableInput(long reservedOutput) {
            long active = activeContext > 0 ? activeContext : maximumContext;
            if (active <= 0) return 8192;
            long reserve = Math.max(512, reservedOutput);
            long safety = Math.max(256, Math.min(2048, active / 20));
            return Math.max(256, active - reserve - safety);
        }
    }

    private volatile ContextInfo detectedContext =
            new ContextInfo("Unknown","",0,0,"unavailable",false);

    private ContextInfo detectContextForModel(String endpoint, String model) {
        String base = contextApiBase(endpoint);

        ContextInfo x = tryLmStudioContext(base, model);
        if (x != null) return x;

        x = tryLlamaCppContext(base, model);
        if (x != null) return x;

        x = tryOllamaContext(base, model);
        if (x != null) return x;

        x = tryGenericContext(base, model);
        if (x != null) return x;

        return new ContextInfo("Unknown",model,0,0,"unavailable",false);
    }

    private String contextApiBase(String endpoint) {
        String s=endpoint==null?"":endpoint.trim();
        while(s.endsWith("/")) s=s.substring(0,s.length()-1);
        if(s.endsWith("/chat/completions"))
            s=s.substring(0,s.length()-"/chat/completions".length());
        if(s.endsWith("/v1")) s=s.substring(0,s.length()-3);
        return s;
    }

    private ContextInfo tryLmStudioContext(String base,String model) {
        try {
            String json=httpGet(base+"/api/v1/models");
            if(json==null) return null;

            String obj=findModelObject(json,model);
            long active=findLong(obj,"context_length");
            long max=findLong(obj,"max_context_length");

            // Search explicitly for loaded_instances config context_length.
            if(active<=0) {
                Matcher m=Pattern.compile(
                    "(?s)loaded_instances.*?config.*?[\"']context_length[\"']\\s*:\\s*(\\d+)")
                    .matcher(obj);
                if(m.find()) active=Long.parseLong(m.group(1));
            }

            if(active>0 || max>0) {
                if(active<=0) active=max;
                if(max<=0) max=active;
                return new ContextInfo("LM Studio",model,active,max,
                        "/api/v1/models",true);
            }
        } catch(Exception ignored){}
        return null;
    }

    private ContextInfo tryLlamaCppContext(String base,String model) {
        try {
            String json=httpGet(base+"/props");
            long n=findLong(json,"n_ctx");
            if(n>0) return new ContextInfo("llama.cpp",model,n,n,
                    "/props",true);
        } catch(Exception ignored){}
        return null;
    }

    private ContextInfo tryOllamaContext(String base,String model) {
        try {
            String body="{\"model\":\""+jsonEscape(model)+"\"}";
            String json=httpPost(base+"/api/show",body);
            if(json==null) return null;

            long max=0, active=0;
            Matcher cm=Pattern.compile(
                "[\"'][^\"']*\\.context_length[\"']\\s*:\\s*(\\d+)")
                .matcher(json);
            if(cm.find()) max=Long.parseLong(cm.group(1));

            Matcher nm=Pattern.compile("(?i)\\bnum_ctx\\s+(\\d+)")
                .matcher(json);
            if(nm.find()) active=Long.parseLong(nm.group(1));

            if(active<=0) active=max;
            if(active>0 || max>0) {
                if(max<=0) max=active;
                return new ContextInfo("Ollama",model,active,max,
                        "/api/show",true);
            }
        } catch(Exception ignored){}
        return null;
    }

    private ContextInfo tryGenericContext(String base,String model) {
        try {
            String json=httpGet(base+"/v1/models");
            if(json==null) return null;
            String obj=findModelObject(json,model);
            long n=findLong(obj,"context_length","context_window",
                    "max_context_length","max_context_tokens","n_ctx","num_ctx");
            if(n>0) return new ContextInfo("OpenAI-compatible",model,n,n,
                    "/v1/models",true);
        } catch(Exception ignored){}
        return null;
    }

    private String findModelObject(String json,String model) {
        if(json==null) return "";
        if(model==null || model.isBlank()) return json;
        String quoted=Pattern.quote(model);
        Matcher m=Pattern.compile(
            "(?s)\\{[^{}]*?[\"'](?:id|key|model)[\"']\\s*:\\s*[\"']"
            +quoted+"[\"'][^{}]*?\\}").matcher(json);
        return m.find()?m.group():json;
    }

    private long findLong(String json,String...keys) {
        if(json==null) return 0;
        for(String key:keys) {
            Matcher m=Pattern.compile(
                "[\"']"+Pattern.quote(key)+"[\"']\\s*:\\s*(\\d+)")
                .matcher(json);
            if(m.find()) {
                try { return Long.parseLong(m.group(1)); }
                catch(Exception ignored){}
            }
        }
        return 0;
    }

    private String firstStringValue(String json,String key) {
        if(json==null)return null;
        Matcher m=Pattern.compile(
            "[\"']"+Pattern.quote(key)+"[\"']\\s*:\\s*[\"']([^\"']*)[\"']")
            .matcher(json);
        return m.find()?m.group(1):null;
    }

    private String jsonEscape(String s) {
        if(s==null)return "";
        return s.replace("\\","\\\\").replace("\"","\\\"")
                .replace("\r","\\r").replace("\n","\\n");
    }

    private String httpGet(String url) throws IOException {
        HttpURLConnection c=(HttpURLConnection)URI.create(url).toURL().openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(1500);
        c.setReadTimeout(2500);
        c.setRequestProperty("Accept","application/json");
        int status=c.getResponseCode();
        if(status<200 || status>=300){c.disconnect();return null;}
        try(InputStream in=c.getInputStream()){
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        } finally {c.disconnect();}
    }

    private String httpPost(String url,String body) throws IOException {
        HttpURLConnection c=(HttpURLConnection)URI.create(url).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(1500);
        c.setReadTimeout(2500);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json");
        c.setRequestProperty("Accept","application/json");
        try(OutputStream out=c.getOutputStream()){
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status=c.getResponseCode();
        if(status<200 || status>=300){c.disconnect();return null;}
        try(InputStream in=c.getInputStream()){
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        } finally {c.disconnect();}
    }

    private void refreshDetectedContext() {
        String endpoint=endpointField.getText().trim();
        String model=modelField.getText().trim();

        ContextInfo info=detectContextForModel(endpoint,model);
        detectedContext=info;

        SwingUtilities.invokeLater(()->{
            if(info.activeContext>0) {
                contextLabel.setText("Context: "
                    +formatContext(info.activeContext)
                    +" / "+formatContext(info.maximumContext)
                    +" ("+info.provider+")");
                contextLabel.setToolTipText(
                    "Active context: "+info.activeContext+" tokens\n"
                    +"Maximum context: "+info.maximumContext+" tokens\n"
                    +"Source: "+info.source);
            } else {
                contextLabel.setText("Context: unavailable");
                contextLabel.setToolTipText(
                    "Runtime context could not be detected.");
            }
        });
    }

    private String formatContext(long n) {
        if(n>=1_000_000)return String.format(Locale.US,"%.1fM",n/1_000_000.0);
        if(n>=1_000)return String.format(Locale.US,"%.0fK",n/1_000.0);
        return Long.toString(n);
    }



    private void commitMaxTokensText() {
        JComponent editor = maxTokensField;
        if (!(editor instanceof JSpinner.NumberEditor numberEditor)) return;

        JFormattedTextField field = numberEditor.getTextField();
        String text = field.getText().trim();

        if (text.isEmpty()) return;

        try {
            long value = Long.parseLong(text);

            // Keep the configured range, but don't interfere while typing.
            if (value < 256) value = 256;
            if (value > 131072) value = 131072;

            maxTokensField.setText(Long.toString(value));
            field.setText(Long.toString(value));
        } catch (NumberFormatException ignored) {
            // Restore the last valid value only after invalid editing is
            // finished, never during keystrokes.
            SwingUtilities.invokeLater(() -> {
                Object value = getMaxTokens();
                field.setText(String.valueOf(value));
            });
        }
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

        JPanel operationInfo = new JPanel(new GridLayout(2, 1, 0, 1));
        operationInfo.add(operationLabel);
        contextLabel.setFont(contextLabel.getFont().deriveFont(Font.PLAIN, 11f));
        operationInfo.add(contextLabel);
        operationBar.add(operationInfo, BorderLayout.WEST);
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
        g.gridx = 3; config.add(maxTokensField, g);
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
        chatArea.setContentType("text/html");
        chatArea.setEditable(false);
        chatArea.setEditorKit(new HTMLEditorKit());
        chatArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        chatArea.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        chatArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        chatArea.addHyperlinkListener(new HyperlinkListener() {
            @Override
            public void hyperlinkUpdate(HyperlinkEvent event) {
                if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
                String description = event.getDescription();
                if (description != null && description.startsWith("filetoggle:")) {
                    toggleChatFile(description.substring("filetoggle:".length()));
                }
            }
        });

        renderConversationHtml();

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
            // Only an actual user selection may change the editable model field.
            // Programmatic changes caused by model discovery must never overwrite
            // text the user is currently typing.
            if (syncingModelCombo || !modelCombo.hasFocus()) {
                return;
            }

            Object selected = modelCombo.getSelectedItem();
            if (selected != null && !selected.toString().isBlank()) {
                modelField.setText(selected.toString());
                modelField.setCaretPosition(modelField.getDocument().getLength());
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

        maxTokensField.setEditable(true);
        maxTokensField.setHorizontalAlignment(SwingConstants.RIGHT);
        maxTokensField.addActionListener(e -> normalizeMaxTokensField());
        maxTokensField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                normalizeMaxTokensField();
            }
        });

        // Apply Font Icons after the complete main UI tree exists so every
        // button and label gets the same icon treatment.
        applyFontIconsToComponentTree(root);
        refreshDetectedContext();
    }

    // ---------- Chat ----------

    private boolean looksLikeFileGenerationRequest(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);

        return t.contains("create file")
                || t.contains("create files")
                || t.contains("generate file")
                || t.contains("generate files")
                || t.contains("write file")
                || t.contains("write files")
                || t.contains("update file")
                || t.contains("modify file")
                || t.contains("create project")
                || t.contains("generate project")
                || t.contains("implement this")
                || t.contains("implement the");
    }

    private void sendMessage() {
        if (busy) return;

        saveConfiguration();

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
        refreshDetectedContext();
        if (workspace == null) return;

        loadSessionForWorkspace(workspace);

        inputArea.setText("");
        String messageWithAttachments = buildMessageWithAttachments(text);
        if (looksLikeFileGenerationRequest(text)) {
            messageWithAttachments += "\n\nSYSTEM AGENT NOTE: This request requires direct local file generation. "
                    + "Do NOT paste generated source files as ordinary chat content. "
                    + "Emit each file using <<<FILE:relative/path>>> ... <<<END_FILE>>>."; 
        }
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
                        synchronized (streamingChatText) { streamingChatText.setLength(0); }
                        appendSystem("Operation cancelled by user. Partial LLM response was discarded.");
                        clearAttachments();
                        stopProgressIndicator("Cancelled");
                        setBusy(false, "Cancelled");
                        currentWorker = null;
                        return;
                    }

                    FileWriteResult fileResult = writeFileBlocksSafe(response, workspace);

                    String chatResponse = removeFileBlocksForChat(response);

                    if (fileResult.count() > 0 || !streamedWrittenFiles.isEmpty()) {
                        chatResponse = chatResponse.trim();
                        if (!chatResponse.isEmpty()) {
                            chatResponse += "\n\n";
                        }

                        chatResponse += "Files generated/updated:\n";
                        LinkedHashSet<String> allWritten =
                                new LinkedHashSet<>(streamedWrittenFiles);
                        for (Path file : fileResult.files)
                            allWritten.add(file.toAbsolutePath().normalize().toString());

                        for (String absolute : allWritten) {
                            try {
                                chatResponse += "✓ "
                                        + workspace.relativize(Paths.get(absolute))
                                        + "\n";
                            } catch (Exception ignored) {
                                chatResponse += "✓ " + absolute + "\n";
                            }
                        }

                        chatResponse += "\nWorkspace: " + workspace;
                        refreshWorkspace();
                    }

                    if (fileResult.errors.size() > 0) {
                        chatResponse += "\n\nFile generation status:\n";
                        for (String error : fileResult.errors) {
                            chatResponse += "• " + error + "\n";
                        }
                    }

                    // Store the complete response for future conversation context,
                    // but display only the human-readable response and file summary.
                    addMessage("assistant", response);

                    synchronized (streamingChatText) {
                        streamingChatText.setLength(0);
                    }

                    removeLastStreamingOutput();
                    appendAssistant(chatResponse);

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
                        synchronized (streamingChatText) { streamingChatText.setLength(0); }
                        appendSystem("ERROR: " + rootMessage(ex));
                        stopProgressIndicator("Error");
                        setBusy(false, "Error");
                        currentWorker = null;
                    }
                });
            }
        });
    }

    private void refreshModelContext(String model) {
        if (model == null || model.isBlank()) return;

        CompletableFuture.runAsync(() -> {
            try {
                String endpoint = normalizeEndpoint(endpointField.getText());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/models"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                int detected = findContextLength(response.body(), model);

                if (detected > 0) {
                    modelContextTokens = detected;
                    detectedContextModel = model;
                    SwingUtilities.invokeLater(() ->
                            contextLabel.setText("Context: " + formatTokenCount(detected) + " tokens"));
                } else {
                    SwingUtilities.invokeLater(() ->
                            contextLabel.setText("Context: server did not report it"));
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        contextLabel.setText("Context: unavailable"));
            }
        });
    }

    private int findContextLength(String json, String model) {
        // Look for the selected model's object first.
        int modelPos = json.indexOf("\"id\":\"" + model + "\"");
        if (modelPos < 0) modelPos = json.indexOf("\"id\": \"" + model + "\"");

        String candidate = json;
        if (modelPos >= 0) {
            int start = json.lastIndexOf('{', modelPos);
            int end = findMatchingObjectEnd(json, start);
            if (start >= 0 && end > start) candidate = json.substring(start, end + 1);
        }

        String[] keys = {
                "context_length", "contextLength",
                "max_context_length", "maxContextLength",
                "n_ctx", "n_ctx_train",
                "max_model_len", "max_position_embeddings"
        };

        for (String key : keys) {
            Integer value = findNumericProperty(candidate, key);
            if (value != null && value > 0 && value <= 10_000_000) return value;
        }

        for (String key : keys) {
            Integer value = findNumericProperty(json, key);
            if (value != null && value > 0 && value <= 10_000_000) return value;
        }

        return 0;
    }

    private Integer findNumericProperty(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)")
                .matcher(json);
        if (!m.find()) return null;
        try { return Integer.parseInt(m.group(1)); }
        catch (NumberFormatException e) { return null; }
    }

    private int findMatchingObjectEnd(String json, int start) {
        if (start < 0) return -1;
        int depth=0;
        boolean quoted=false, escaped=false;

        for (int i=start;i<json.length();i++) {
            char c=json.charAt(i);
            if (quoted) {
                if (escaped) escaped=false;
                else if (c=='\\') escaped=true;
                else if (c=='"') quoted=false;
                continue;
            }
            if (c=='"') quoted=true;
            else if (c=='{') depth++;
            else if (c=='}') {
                depth--;
                if (depth==0) return i;
            }
        }
        return -1;
    }

    private String formatTokenCount(int tokens) {
        if (tokens >= 1_000_000)
            return String.format(Locale.ROOT, "%.1fM", tokens/1_000_000.0);
        if (tokens >= 1000)
            return String.format(Locale.ROOT, "%.1fK", tokens/1000.0);
        return Integer.toString(tokens);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;

        int chars=text.length();
        int nonAscii=0;
        for (int i=0;i<chars;i++) if (text.charAt(i)>127) nonAscii++;

        double charsPerToken = nonAscii > chars/10 ? 2.2 : 3.2;
        return Math.max(1, (int)Math.ceil((chars/charsPerToken)*1.10));
    }

    private int estimateConversationTokens() {
        int total=estimateTokens(SYSTEM_PROMPT);
        for (Map<String,String> m:messages)
            total += estimateTokens(m.get("content")) + 8;
        return total;
    }

    private int getAttachmentTokenBudget() {
        int context=Math.max(modelContextTokens,2048);
        int conversation=estimateConversationTokens();
        int output=((Number)getMaxTokens()).intValue();

        int available=context-conversation-output-512;
        return Math.max(0,available);
    }

    private int tokenBudgetToChars(int tokens) {
        return Math.max(0,(int)Math.min(Integer.MAX_VALUE,
                Math.ceil(tokens*3.0)));
    }

    private String callChatCompletion(String model) throws Exception {
        String endpoint = normalizeEndpoint(endpointField.getText());
        String url = endpoint + "/chat/completions";

        List<Map<String, String>> requestMessages = new ArrayList<>();
        Path currentWorkspace = getWorkspace();
        String fileProtocol =
                SYSTEM_PROMPT
                + "\n\nCURRENT WORKSPACE:\n"
                + currentWorkspace
                + "\n\nSESSION CONTEXT:\n"
                + "Previously generated files are automatically supplied as hidden context "
                + "on every request. Treat their current disk contents as authoritative. "
                + "Do not ask the user to reattach them. "
                + "\n\nFILE GENERATION PROTOCOL:\n"
                + "When creating or updating files, output each file using exactly:\n"
                + "<<<FILE:path/to/file.ext>>>\n"
                + "complete file contents\n"
                + "<<<END_FILE>>>\n"
                + "Do not use markdown fences around FILE blocks. "
                + "The desktop agent WILL write every FILE block directly to the selected workspace. "
                + "If the user asks for files, you MUST use FILE blocks; do not merely paste source code "
                + "in the normal response. After the FILE blocks, provide only a short summary. "
                + "Do not say that the user needs to copy the code manually.";
        requestMessages.add(Map.of("role", "system", "content", fileProtocol));

        // Hidden session context: previous conversation is already in messages,
        // while previously generated files are re-read from disk and supplied on
        // every request. Nothing from this context is rendered in the chat UI.
        String sessionContext = buildHiddenSessionContext();
        if (!sessionContext.isBlank()) {
            requestMessages.add(Map.of(
                    "role", "system",
                    "content", sessionContext));
        }

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
                .append(((Number) getMaxTokens()).intValue()).append(",");
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
        streamedFileBuffer.setLength(0);
        streamedWrittenFiles.clear();

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
                appendStreamingTextToChat("\n\nLOCAL LLM\n----------------------------------------\n");
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


    private void processStreamingFileBlocks() {
        Path workspace = getWorkspaceForWorker();
        if (workspace == null) return;

        while (true) {
            String buffer = streamedFileBuffer.toString();
            int start = findFileStart(buffer, 0);
            if (start < 0) {
                if (buffer.length() > 128)
                    streamedFileBuffer.delete(0, buffer.length() - 128);
                return;
            }

            int headerEnd = buffer.indexOf(">>>", start);
            if (headerEnd < 0) {
                if (start > 0) streamedFileBuffer.delete(0, start);
                return;
            }

            int prefixLength = fileHeaderPrefixLength(buffer, start);
            if (prefixLength < 0 || headerEnd <= start + prefixLength) {
                streamedFileBuffer.delete(0, Math.min(headerEnd + 3, streamedFileBuffer.length()));
                continue;
            }

            String relative = buffer.substring(start + prefixLength, headerEnd).trim();
            relative = cleanGeneratedPath(relative);
            int end = findEndFileMarker(buffer, headerEnd + 3);
            if (end < 0) {
                if (start > 0) streamedFileBuffer.delete(0, start);
                return;
            }

            String content = buffer.substring(headerEnd + 3, end);
            streamedFileBuffer.delete(0, end + 14);
            writeStreamingFile(workspace, relative, content);
        }
    }

    private int fileHeaderPrefixLength(String s, int start) {
        if (start < 0 || start >= s.length()) return -1;

        if (s.regionMatches(true, start, "<<<FILE:", 0, 8)) return 8;
        if (s.regionMatches(true, start, "<<<FILE ", 0, 8)) return 8;

        return -1;
    }

    private String cleanGeneratedPath(String path) {
        if (path == null) return "";

        String p = path.trim();

        // Remove common quoting accidentally emitted by an LLM.
        p = p.replaceAll("^[\"'`]+", "");
        p = p.replaceAll("[\"'`]+$", "");
        p = p.trim();

        // Be defensive against a malformed marker being included in the path.
        while (p.startsWith("<")) {
            p = p.substring(1).trim();
        }

        while (p.startsWith(":")) {
            p = p.substring(1).trim();
        }

        // Never allow an absolute Windows path or Unix path from the model
        // to escape the selected workspace.
        p = p.replace('\\', '/');

        while (p.startsWith("/")) {
            p = p.substring(1);
        }

        return p;
    }

    private int findFileStart(String s, int from) {
        String u=s.toUpperCase(Locale.ROOT);
        int a=u.indexOf("<<<FILE:",from), b=u.indexOf("<<<FILE ",from);
        if(a<0)return b; if(b<0)return a; return Math.min(a,b);
    }

    private int findEndFileMarker(String s, int from) {
        String u=s.toUpperCase(Locale.ROOT);
        int a=u.indexOf("<<<END_FILE>>>",from), b=u.indexOf("<<<END FILE>>>",from);
        if(a<0)return b; if(b<0)return a; return Math.min(a,b);
    }


    /**
     * Loads the generated-file manifest for the selected workspace.
     * The manifest is hidden and contains only relative paths.
     */
    private synchronized void loadSessionForWorkspace(Path workspace) {
        if (workspace == null) return;

        Path normalized = workspace.toAbsolutePath().normalize();

        if (normalized.equals(loadedSessionWorkspace)) return;

        sessionGeneratedFiles.clear();
        loadedSessionWorkspace = normalized;

        Path sessionFile = normalized.resolve(SESSION_FILE_NAME);

        if (!Files.isRegularFile(sessionFile)) return;

        try {
            for (String line : Files.readAllLines(
                    sessionFile, StandardCharsets.UTF_8)) {

                String path = line.trim();

                if (path.isEmpty() || path.startsWith("#")) continue;

                try {
                    Path file = safeResolve(normalized, path);

                    if (Files.isRegularFile(file)) {
                        sessionGeneratedFiles.add(
                                normalized.relativize(file)
                                        .toString()
                                        .replace('\\', '/'));
                    }
                } catch (Exception ignored) {
                    // Ignore stale/invalid entries from an old session.
                }
            }
        } catch (IOException ignored) {
            // Session persistence is best-effort and must never prevent chatting.
        }
    }

    private synchronized void registerGeneratedFile(Path file) {
        if (file == null) return;

        try {
            Path workspace = getWorkspaceForWorker();
            if (workspace == null) return;

            Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
            Path normalizedFile = file.toAbsolutePath().normalize();

            if (!normalizedFile.startsWith(normalizedWorkspace)) return;
            if (!Files.isRegularFile(normalizedFile)) return;

            loadSessionForWorkspace(normalizedWorkspace);

            String relative = normalizedWorkspace
                    .relativize(normalizedFile)
                    .toString()
                    .replace('\\', '/');

            if (relative.equals(SESSION_FILE_NAME)) return;

            sessionGeneratedFiles.add(relative);
            saveSessionManifest(normalizedWorkspace);
        } catch (Exception ignored) {
            // Do not fail file generation because session persistence failed.
        }
    }

    private synchronized void saveSessionManifest(Path workspace) {
        if (workspace == null) return;

        Path sessionFile = workspace.resolve(SESSION_FILE_NAME);

        try {
            Files.createDirectories(workspace);

            List<String> lines = new ArrayList<>();
            lines.add("# LocalLLMSwingAgent generated-file session");
            lines.add("# This file is maintained automatically. Do not edit while the agent is running.");

            List<String> files = new ArrayList<>(sessionGeneratedFiles);
            Collections.sort(files);

            for (String file : files) {
                try {
                    Path resolved = safeResolve(workspace, file);

                    if (Files.isRegularFile(resolved)) {
                        lines.add(file);
                    }
                } catch (Exception ignored) {
                }
            }

            Files.write(
                    sessionFile,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /**
     * Builds hidden context for every request.
     *
     * Previous generated files are read fresh from disk. Therefore if the user
     * manually edits a generated file between messages, the LLM sees the
     * current contents rather than stale contents from the previous response.
     */
    private String buildHiddenSessionContext() {
        Path workspace = getWorkspaceForWorker();
        if (workspace == null) return "";

        loadSessionForWorkspace(workspace);

        StringBuilder context = new StringBuilder();

        context.append(
                "HIDDEN LOCAL CODING SESSION CONTEXT\\n"
                + "The following files were previously generated or modified "
                + "by this agent in the current persistent session.\\n"
                + "These files are supplied automatically on every chat request.\\n"
                + "Use them as the current source of truth. Do not tell the user "
                + "that they were automatically attached.\\n\\n");

        int count = 0;
        long contextBudgetChars = getUsableContextBudget() * 4L;
        long usedChars = context.length();

        List<String> files = new ArrayList<>(sessionGeneratedFiles);
        Collections.sort(files);

        for (String relative : files) {
            try {
                Path file = safeResolve(workspace, relative);

                if (!Files.isRegularFile(file)) continue;

                String content = readSessionFileContent(file);

                if (usedChars + content.length() + relative.length() + 200
                        > contextBudgetChars) {
                    context.append("===== ADDITIONAL SESSION FILES OMITTED =====\n");
                    break;
                }

                context.append(
                        "===== BEGIN SESSION FILE: ")
                        .append(relative)
                        .append(" =====\\n");

                context.append(content);

                if (!content.endsWith("\\n")) {
                    context.append("\\n");
                }

                context.append(
                        "===== END SESSION FILE: ")
                        .append(relative)
                        .append(" =====\\n\\n");

                count++;
                usedChars = context.length();

            } catch (Exception e) {
                context.append(
                        "===== SESSION FILE UNAVAILABLE: ")
                        .append(relative)
                        .append(" =====\\n");
            }
        }

        if (count == 0) {
            return "";
        }

        return context.toString();
    }


    private void normalizeMaxTokensField() {
        String text = maxTokensField.getText().trim();
        if (text.isEmpty()) return;

        try {
            int value = Integer.parseInt(text);
            value = Math.max(256, Math.min(131072, value));
            maxTokensField.setText(Integer.toString(value));
        } catch (NumberFormatException ignored) {
            maxTokensField.setText(Integer.toString(getMaxTokens()));
        }
    }

    private int getMaxTokens() {
        try {
            int value = Integer.parseInt(maxTokensField.getText().trim());
            return Math.max(256, Math.min(131072, value));
        } catch (Exception e) {
            return 8192;
        }
    }

    private long getUsableContextBudget() {
        long active=detectedContext.activeContext;
        if(active<=0) return 8192;
        long reservedOutput=4096;
        long safety=Math.max(256,Math.min(2048,active/20));
        return Math.max(256,active-reservedOutput-safety);
    }

    private String readSessionFileContent(Path file) throws IOException {
        long size = Files.size(file);

        // Avoid sending arbitrary binary files into the LLM session context.
        if (size > 5_000_000) {
            return "[File is larger than 5 MB; contents omitted from hidden session context.]";
        }

        if (!isLikelyTextFile(file)) {
            return "[Binary file; contents omitted from hidden session context.]";
        }

        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private boolean isLikelyTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        String[] textExtensions = {
                ".java", ".c", ".h", ".cpp", ".hpp", ".cc", ".cxx",
                ".cs", ".py", ".js", ".ts", ".jsx", ".tsx", ".go",
                ".rs", ".kt", ".scala", ".groovy", ".html", ".htm",
                ".css", ".scss", ".json", ".xml", ".yaml", ".yml",
                ".properties", ".ini", ".cfg", ".conf", ".sql", ".csv",
                ".tsv", ".txt", ".md", ".rst", ".tex", ".log",
                ".gradle", ".pom", ".sh", ".bat", ".cmd", ".ps1",
                ".gitignore", ".dockerfile"
        };

        for (String ext : textExtensions) {
            if (name.endsWith(ext)) return true;
        }

        // Files without an extension are often source/config files.
        return !name.contains(".");
    }

    private Path getWorkspaceForWorker() {
        try {
            Path p=Paths.get(workspaceField.getText().trim()).toAbsolutePath().normalize();
            if(!Files.exists(p)) Files.createDirectories(p);
            return Files.isDirectory(p)?p:null;
        } catch(Exception e){ return null; }
    }

    private void writeStreamingFile(Path workspace,String relative,String content) {
        relative=relative.replaceAll("^[\"'`]+|[\"'`]+$","").trim();
        if(relative.isEmpty()){ showFileWriteStatus("✗ FILE WRITE FAILED: empty path"); return; }

        try {
            Path file=safeResolve(workspace,relative);
            if(file.getParent()!=null) Files.createDirectories(file.getParent());

            Files.writeString(file,normalizeGeneratedFileContent(content),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            String absolute = file.toAbsolutePath().normalize().toString();
            streamedWrittenFiles.add(absolute);
            registerGeneratedFile(file);
            showFileWriteStatus("✓ FILE WRITTEN: "+file);
            SwingUtilities.invokeLater(this::refreshWorkspace);
        } catch(Exception e) {
            showFileWriteStatus("✗ FILE WRITE FAILED: "+relative+" -> "+e.getMessage());
        }
    }

    private void showFileWriteStatus(String message) {
        SwingUtilities.invokeLater(()->{
            statusLabel.setText(message);
            operationLabel.setText(message);
        });
    }

    private void appendStreamingText(String text) {
        if (text == null || text.isEmpty()) return;

        streamedFileBuffer.append(text);
        processStreamingFileBlocks();

        SwingUtilities.invokeLater(() -> {
            if (!cancelRequested) {
                appendStreamingTextToChat(text);
                operationLabel.setText("LLM is generating...  "
                        + formatElapsed(System.currentTimeMillis()
                        - operationStartMillis));
            }
        });
    }

    private static final class FileWriteResult {
        final List<Path> files = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        int count() { return files.size(); }
    }

    private FileWriteResult writeFileBlocksSafe(String response, Path workspace) {
        FileWriteResult result = new FileWriteResult();

        if (response == null || response.isBlank()) {
            result.errors.add("LLM returned an empty response.");
            return result;
        }

        Matcher matcher = FILE_BLOCK_PATTERN.matcher(response);
        int blocks = 0;

        while (matcher.find()) {
            blocks++;

            String relative = matcher.group(1).trim();
            String content = matcher.group(2);

            // Strip accidental quotes around the path.
            relative = relative.replaceAll("^[\"'`]+|[\"'`]+$", "").trim();

            if (relative.isEmpty()) {
                result.errors.add("FILE block #" + blocks + " has an empty path.");
                continue;
            }

            try {
                Path file = safeResolve(workspace, relative);

                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                Files.writeString(
                        file,
                        normalizeGeneratedFileContent(content),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);

                result.files.add(file);
                registerGeneratedFile(file);

            } catch (Exception e) {
                result.errors.add(relative + " -> " + e.getMessage());
            }
        }

        if (blocks == 0) {
            result.errors.add(
                    "No FILE blocks were found in the LLM response. "
                    + "The model must use <<<FILE:path>>> ... <<<END_FILE>>> "
                    + "for direct file generation.");
        }

        return result;
    }

    private String normalizeGeneratedFileContent(String content) {
        if (content == null) return "";

        String result = content;

        // Remove only an accidental fence surrounding the entire block.
        result = result.replaceFirst(
                "(?s)^\\\\s*```(?:[A-Za-z0-9_+.#-]+)?\\\\s*\\\\R", "");
        result = result.replaceFirst(
                "(?s)\\\\R\\\\s*```\\\\s*$", "");

        // Remove an accidental leading/trailing blank line.
        return result.replaceFirst("^\\\\s*\\\\R", "")
                .replaceFirst("\\\\R\\\\s*$", "\\\\n");
    }

    private String removeFileBlocksForChat(String response) {
        if (response == null) return "";

        Matcher matcher = FILE_BLOCK_PATTERN.matcher(response);
        String cleaned = matcher.replaceAll("");

        return cleaned.replaceAll("\\\\n{3,}", "\\\\n\\\\n").trim();
    }

    private void removeLastStreamingOutput() {
        renderConversationHtml();
    }

    private String htmlEscape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
        if (workspace == null) {
            throw new IOException("Workspace is not configured.");
        }

        String cleaned = cleanGeneratedPath(relative);

        if (cleaned.isEmpty()) {
            throw new IOException("Generated file path is empty.");
        }

        Path candidate = Paths.get(cleaned);

        if (candidate.isAbsolute()) {
            throw new IOException("Absolute generated path is not allowed: " + relative);
        }

        Path resolved = workspace.resolve(candidate).normalize();
        Path root = workspace.toAbsolutePath().normalize();

        if (!resolved.startsWith(root)) {
            throw new IOException(
                    "Generated path escapes workspace: " + relative);
        }

        return resolved;
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

        int attachmentTokenBudget = getAttachmentTokenBudget();
        int remaining = tokenBudgetToChars(attachmentTokenBudget);

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
                int allowance = Math.min(remaining, Math.max(1024, tokenBudgetToChars(Math.max(256, attachmentTokenBudget / 4))));
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
            saveConfiguration();
            loadSessionForWorkspace(getWorkspaceForWorker());
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

                    syncingModelCombo = true;
                    try {
                        if (!current.isBlank()) {
                            // Preserve the exact text currently in the model field.
                            modelCombo.setSelectedItem(current);

                            if (modelCombo.getSelectedItem() == null) {
                                modelCombo.addItem(current);
                                modelCombo.setSelectedItem(current);
                            }
                        } else if (!ids.isEmpty()) {
                            // Only initialize it when the user has not typed anything.
                            modelCombo.setSelectedIndex(0);
                            Object selected = modelCombo.getSelectedItem();

                            if (modelField.getText().trim().isEmpty()
                                    && selected != null) {
                                modelField.setText(selected.toString());
                            }
                        }
                    } finally {
                        syncingModelCombo = false;
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

    private void appendHtmlFragment(String fragment) {
        // Kept for compatibility with the chat methods. The V14 renderer
        // rebuilds the complete HTML document instead of inserting fragments
        // into an HTMLDocument, which prevents Swing HTML layout corruption.
        renderConversationHtml();
    }

    private void appendStreamingTextToChat(String text) {
        if (text == null || text.isEmpty()) return;

        synchronized (streamingChatText) {
            streamingChatText.append(text);
        }

        renderConversationHtml();
    }

    private void renderConversationHtml() {
        StringBuilder html = new StringBuilder();

        html.append("<html><head><style>")
                .append("body{font-family:Segoe UI,Arial,sans-serif;font-size:14px;"
                        + "margin:6px;color:#202020;}")
                .append(".message{margin-bottom:18px;}")
                .append(".role{font-weight:bold;margin-bottom:5px;}")
                .append(".bubble{margin-left:2px;line-height:1.4;}")
                .append("a.file-toggle{display:block;padding:7px 9px;margin:7px 0 3px 0;"
                        + "border:1px solid #c8c8c8;background:#f2f2f2;"
                        + "color:#202020;text-decoration:none;}")
                .append("pre.file-content{margin:0 0 8px 8px;padding:9px;"
                        + "border:1px solid #d0d0d0;background:#fafafa;"
                        + "font-family:Consolas,Monaco,monospace;font-size:12px;}")
                .append(".streaming{color:#444;}")
                .append("</style></head><body>");

        for (Map<String,String> message : messages) {
            String role=message.get("role");
            String content=message.get("content");

            if ("user".equals(role)) {
                html.append("<div class='message'>")
                        .append("<div class='role'>YOU</div>")
                        .append("<div class='bubble'>")
                        .append(htmlEscape(content).replace("\n","<br>"))
                        .append("</div></div>");
            } else if ("assistant".equals(role)) {
                html.append("<div class='message'>")
                        .append("<div class='role'>LOCAL LLM</div>")
                        .append("<div class='bubble'>");
                appendAssistantContentHtml(html, content);
                html.append("</div></div>");
            } else if ("system".equals(role)) {
                html.append("<div class='message'>")
                        .append("<div class='role'>SYSTEM</div>")
                        .append("<div class='bubble'>")
                        .append(htmlEscape(content).replace("\n","<br>"))
                        .append("</div></div>");
            }
        }

        String streaming;
        synchronized (streamingChatText) {
            streaming = streamingChatText.toString();
        }

        if (!streaming.isEmpty()) {
            html.append("<div class='message streaming'>")
                    .append("<div class='role'>LOCAL LLM</div>")
                    .append("<div class='bubble'>")
                    .append(htmlEscape(removeFileBlocksForChat(streaming))
                            .replace("\n","<br>"))
                    .append("</div></div>");
        }

        html.append("</body></html>");

        final String rendered = html.toString();

        SwingUtilities.invokeLater(() -> {
            chatArea.setText(rendered);
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    private void appendAssistantContentHtml(StringBuilder html, String content) {
        if (content == null) return;

        Matcher matcher = FILE_BLOCK_PATTERN.matcher(content);
        int last=0;

        while (matcher.find()) {
            appendNormalAssistantHtml(html, content.substring(last, matcher.start()));

            String relative=cleanGeneratedPath(matcher.group(1));
            String fileContent=matcher.group(2);
            String key=createChatFileKey(relative,fileContent);
            boolean expanded=expandedChatFiles.contains(key);

            html.append("<a class='file-toggle' href='filetoggle:")
                    .append(htmlEscapeAttribute(key))
                    .append("'>")
                    .append(expanded ? "▼" : "▶")
                    .append(" &nbsp; ")
                    .append(htmlEscape(relative))
                    .append("</a>");

            if (expanded) {
                html.append("<pre class='file-content'>")
                        .append(htmlEscape(fileContent))
                        .append("</pre>");
            }

            last=matcher.end();
        }

        appendNormalAssistantHtml(html, content.substring(last));
    }

    private void appendNormalAssistantHtml(StringBuilder html, String text) {
        if (text == null || text.isEmpty()) return;
        html.append(htmlEscape(text).replace("\n","<br>"));
    }

    private String createChatFileKey(String relative, String content) {
        return relative + "#" + Integer.toHexString(
                Objects.hash(relative, content));
    }

    private String htmlEscapeAttribute(String text) {
        return htmlEscape(text)
                .replace("%","%25")
                .replace("#","%23")
                .replace(" ","%20")
                .replace(":","%3A")
                .replace("/","%2F")
                .replace("\\","%5C");
    }

    private void toggleChatFile(String encodedKey) {
        String key=encodedKey
                .replace("%5C","\\")
                .replace("%2F","/")
                .replace("%3A",":")
                .replace("%20"," ")
                .replace("%23","#")
                .replace("%25","%");

        if (expandedChatFiles.contains(key))
            expandedChatFiles.remove(key);
        else
            expandedChatFiles.add(key);

        renderConversationHtml();
    }



    private void appendUser(String text) {
        appendHtmlFragment("<div class='message user'>"
                + "<div class='role'><b>YOU</b></div>"
                + "<div class='bubble'>"
                + htmlEscape(text).replace("\n", "<br>")
                + "</div></div>");
    }

    private void appendAssistant(String text) {
        renderConversationHtml();
    }

    private void appendSystem(String text) {
        appendHtmlFragment("<div class='message system'>"
                + "<div class='role'><b>SYSTEM</b></div>"
                + "<div class='bubble'>"
                + htmlEscape(text).replace("\n", "<br>")
                + "</div></div>");
    }

    private void addMessage(String role, String content) {
        messages.add(new HashMap<>(Map.of("role", role, "content", content)));

        // Keep the request reasonably sized.
        // System prompt is always added separately.
        while (messages.size() > 30) {
            messages.remove(0);
        }
    }

    private String getPlainChatText() {
        StringBuilder out=new StringBuilder();

        for (Map<String,String> message:messages) {
            String role=message.get("role");
            String content=message.get("content");

            out.append("\n\n")
                    .append("user".equals(role) ? "YOU" : "LOCAL LLM")
                    .append("\n----------------------------------------\n");

            if ("assistant".equals(role) && content!=null) {
                Matcher m=FILE_BLOCK_PATTERN.matcher(content);
                int last=0;
                while(m.find()) {
                    out.append(content.substring(last,m.start()));
                    out.append("\n[FILE: ")
                            .append(cleanGeneratedPath(m.group(1)))
                            .append("]\n")
                            .append(m.group(2))
                            .append("\n[/FILE]\n");
                    last=m.end();
                }
                out.append(content.substring(last));
            } else {
                out.append(content==null?"":content);
            }
        }

        return out.toString().trim();
    }

    private void clearConversation() {
        messages.clear();
        expandedChatFiles.clear();
        synchronized (streamingChatText) { streamingChatText.setLength(0); }
        chatArea.setText("<html><body></body></html>");
        appendSystem("Conversation cleared.");
    }

    private void copyChat() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(getPlainChatText()), null);
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
                        getPlainChatText(), StandardCharsets.UTF_8);
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
        // The editable text field is authoritative. The combo box is only a
        // discovery/selection convenience and must never override manual input.
        String field = modelField.getText().trim();
        if (!field.isBlank()) return field;

        return Objects.toString(modelCombo.getSelectedItem(), "").trim();
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

            LocalLLMSwingAgentV19 app = new LocalLLMSwingAgentV19();
            app.setVisible(true);
        });
    }
}
