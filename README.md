# Local LLM tool 

Support direct file creation 

A standalone Java Swing desktop client for interacting with a locally hosted LLM through an OpenAI-compatible API such as LM Studio.

The application is designed as a lightweight local AI coding/document agent with:

- Local LLM chat
- Streaming responses
- Rich chat display
- Multiple file attachments
- Local document extraction
- Image attachments for vision-capable models
- Direct local file generation/update
- Workspace/file browser
- Operation progress indicator
- Cancellation of in-progress LLM operations
- Conversation history
- Configurable model, endpoint, temperature and token limit


<img width="1186" height="774" alt="image" src="https://github.com/user-attachments/assets/46025c86-32d0-49db-a7bd-bf63bb92b59c" />




## Requirements

- Windows, Linux or macOS
- Java 17 or newer
- An OpenAI-compatible local LLM server
- Recommended: LM Studio

The application does not require a cloud API.

---

# 1. Local LLM Server

The application communicates with an OpenAI-compatible endpoint.

For LM Studio, normally use:

```text
http://127.0.0.1:1234/v1
```

Make sure the LM Studio local server is running.

The application sends requests to:

```text
POST /chat/completions
```

and uses:

```json
{
  "stream": true
}
```

so that generated tokens can appear in the UI immediately.

## Recommended LM Studio setup

1. Start LM Studio.
2. Load your preferred model.
3. Start the local server.
4. Enable the OpenAI-compatible API.
5. Verify the server is listening on port 1234.
6. Start LocalLLMSwingAgent.

Example endpoint:

```text
http://127.0.0.1:1234/v1
```

---

# 2. Java Version

The application targets Java 17.

Check your Java version:

```powershell
java -version
```

Compile:

```powershell
javac --release 17 .\LocalLLMSwingAgentV6.java
```

---

# 3. Create Executable JAR

Clean previous class files:

```powershell
Remove-Item .\*.class -ErrorAction SilentlyContinue
Remove-Item .\LocalLLMSwingAgentV6.jar -ErrorAction SilentlyContinue
```

Compile:

```powershell
javac --release 17 -d . .\LocalLLMSwingAgentV6.java
```

Create JAR:

```powershell
jar --create `
    --file LocalLLMSwingAgentV6.jar `
    --main-class LocalLLMSwingAgentV6 `
    *.class
```

Run:

```powershell
java -jar .\LocalLLMSwingAgentV6.jar
```

---

# 4. Main Features

## 4.1 Local LLM Chat

The application provides a desktop chat interface for communicating with a local LLM.

You can:

- Select the loaded local model
- Enter prompts
- Maintain conversation history
- Send multiple messages
- Continue conversations
- Clear the conversation
- Copy the chat
- Save the chat to a local file

No conversation needs to be sent to a cloud service.

---

# 5. Streaming LLM Responses

The application uses OpenAI-compatible streaming.

Instead of waiting for the complete response, tokens are processed as they arrive.

Example:

```text
LOCAL LLM
----------------------------------------
I can help you create
a Java application...
```

The response progressively appears in the chat area.

This is especially important for local models because a 7B/14B model may take several seconds before producing a complete response.

The request uses:

```json
"stream": true
```

and consumes Server-Sent Events (SSE).

Typical LM Studio response:

```text
data: {"choices":[{"delta":{"content":"Hello"}}]}

data: {"choices":[{"delta":{"content":" world"}}]}

data: [DONE]
```

---

# 6. Rich Chat Area

The chat area is designed to make long conversations easier to read.

It separates:

```text
YOU
----------------------------------------

LOCAL LLM
----------------------------------------

SYSTEM
----------------------------------------
```

The chat automatically scrolls toward the latest response.

---

# 7. Multiple File Attachments

You can attach multiple files to a single prompt.

Use:

```text
Attach Files...
```

The file chooser supports multi-selection.

You can also drag files directly into the attachment area.

Example:

```text
C:\Project\src\Main.java
C:\Project\src\Service.java
C:\Project\pom.xml
C:\Project\README.md
C:\Documents\requirements.docx
```

All selected files can be processed together.

---

# 8. Attachment Management

The attachment panel provides:

### Attach Files

Select one or many files.

### Remove Selected

Removes selected attachments.

### Clear

Removes all attachments.

### Preview

Displays the extracted contents of the selected file.

### Double-click

Double-clicking an attachment opens its preview.

### Drag and Drop

Files can be dragged from Windows Explorer directly into the attachment list.

---

# 9. Supported File Types

The application supports a broad range of files.

## Source Code

Examples:

```text
.java
.c
.h
.cpp
.hpp
.cc
.cxx
.cs
.py
.js
.ts
.jsx
.tsx
.go
.rs
.kt
.scala
.groovy
```

## Configuration

```text
.json
.xml
.yaml
.yml
.properties
.ini
.cfg
.conf
```

## Web

```text
.html
.htm
.css
.scss
```

## Database

```text
.sql
.csv
.tsv
```

## Documentation

```text
.txt
.md
.rst
.tex
.log
```

## Build Files

```text
.gradle
.pom
.gitignore
```

---

# 10. Microsoft Office Documents

The application can extract text from:

```text
.docx
.xlsx
.pptx
```

It also supports OpenDocument formats:

```text
.odt
.ods
.odp
```

The implementation reads the document XML contained inside these file formats.

For example:

```text
requirements.docx
```

can be attached and its extracted text is included in the LLM context.

This allows prompts such as:

```text
Create JIRA tasks from the attached SOW.
```

or:

```text
Review this requirements document and identify missing requirements.
```

---

# 11. PDF Support

PDF files can be attached.

The application performs best-effort PDF text extraction.

If Poppler's `pdftotext` is installed and available in PATH, the application can use it for substantially better PDF extraction.

Recommended:

```text
pdftotext
```

Example:

```powershell
pdftotext -layout document.pdf -
```

The application falls back to a Java-based extraction method if `pdftotext` is unavailable.

## Scanned PDFs

A scanned PDF may contain images rather than selectable text.

For reliable scanned-document processing, OCR is required.

Recommended OCR tool:

```text
Tesseract
```

A future version can integrate automatic:

```text
PDF -> OCR -> extracted text -> LLM
```

processing.

---

# 12. Spreadsheet Support

`.xlsx` files are inspected through their internal XML structures.

The application can extract worksheet content and shared strings.

This allows prompts such as:

```text
Analyze the attached Excel file and identify the highest performing products.
```

or:

```text
Create Java code to process the attached spreadsheet.
```

---

# 13. PowerPoint Support

`.pptx` files can be attached.

Text from slides and notes is extracted.

Example:

```text
presentation.pptx
```

can be used with:

```text
Summarize the attached presentation.
```

---

# 14. Archive Support

The application recognizes:

```text
.zip
.jar
.war
.ear
```

Archive contents can be inspected.

For example:

```text
my-application.jar
```

can be attached to inspect its entries.

Archive metadata is provided instead of blindly sending every binary file inside the archive to the LLM.

For source-code analysis, attaching the individual source files is recommended.

---

# 15. Image Support

The application supports common image formats:

```text
.png
.jpg
.jpeg
.gif
.bmp
.webp
.svg
.ico
.tif
.tiff
```

Images can be previewed inside the application.

If the selected local model supports vision and the local OpenAI-compatible server accepts OpenAI-style multimodal messages, image data can be sent using:

```text
data:image/...;base64,...
```

Example request:

```text
Analyze the attached screenshot and explain the error.
```

## Important

A text-only coding model cannot understand image pixels.

A vision-capable model is required for image analysis.

---

# 16. Binary Files

Binary files such as:

```text
.exe
.dll
.class
.bin
```

are not blindly converted into text and sent to the LLM.

Instead, the application provides basic metadata such as:

- File name
- MIME type
- Size

This prevents binary data from unnecessarily consuming the model context.

---

# 17. Context Management

Large attachments can easily exceed a local model's context window.

The application therefore applies attachment limits.

The current implementation uses an overall attachment context limit and per-file truncation.

Large text files are truncated rather than blindly sending the complete file.

The truncation keeps both:

- Beginning of the file
- End of the file

with an indicator in the middle:

```text
[...attachment truncated to fit local LLM context...]
```

This is important for large:

```text
.log
.java
.csv
.json
.xml
```

files.

---

# 18. Direct File Generation

The application is designed to work as a local coding agent.

The LLM can return file blocks that the application detects and writes to the configured workspace.

This allows prompts such as:

```text
Create a Spring Boot REST API for customer management.
```

or:

```text
Create all Java files required for this application.
```

The LLM can generate multiple files.

For example:

```text
src/main/java/com/example/App.java
src/main/java/com/example/Customer.java
src/main/java/com/example/CustomerService.java
pom.xml
README.md
```

The application writes the generated files to the selected workspace.

---

# 19. Workspace

The application provides a local workspace for generated files.

The LLM can generate or update files inside that workspace.

This makes the application suitable for:

- Coding
- Refactoring
- Project generation
- Documentation generation
- Configuration generation
- Code review
- Bug fixing

---

# 20. Safety Around Generated Files

The application resolves generated paths relative to the configured workspace.

This is important because an LLM should not be able to arbitrarily write:

```text
C:\Windows\...
C:\Users\...
```

without passing through the application's workspace/path validation.

Generated files should remain inside the configured workspace.

---

# 21. Progress Indicator

When a request is running, the application displays a dedicated operation bar.

Example:

```text
---------------------------------------------------------------
LLM is generating... 12s       [ Working... ]       [ CANCEL ]
---------------------------------------------------------------
```

The elapsed time updates continuously.

This provides immediate feedback that the application is actively communicating with the local model.

---

# 22. Cancel Operation

The application provides a prominent:

```text
CANCEL
```

button.

Cancellation is supported while the local LLM is generating.

When cancelled:

```text
Cancelling...
```

is displayed.

The HTTP request and worker operation are interrupted.

The partial response is not added to the permanent conversation history.

This is especially useful with larger local models where generation can take a long time.

---

# 23. Background Processing

The LLM request runs outside the Swing Event Dispatch Thread.

This prevents the application window from freezing while the local model is processing.

The UI remains responsive during:

- LLM requests
- Streaming
- File processing
- Document extraction

---

# 24. Model Configuration

The application allows configuration of the local model.

The selected model is sent as:

```json
{
  "model": "your-local-model"
}
```

The model name must match the model exposed by the local server.

---

# 25. Temperature

Temperature can be configured from the application.

Lower values are generally preferable for:

```text
Code generation
Refactoring
Configuration
Structured output
```

Higher values can be useful for:

```text
Creative writing
Brainstorming
Alternative designs
```

---

# 26. Maximum Tokens

The maximum response token count can be configured.

For large code-generation tasks, increase the value if your local model/server supports it.

Example:

```text
4096
8192
16384
32768
```

The actual maximum depends on the selected model and its context window.

---

# 27. Typical Coding Workflow

A typical workflow is:

```text
1. Start LM Studio
        |
        v
2. Load local coding model
        |
        v
3. Start LM Studio API server
        |
        v
4. Start LocalLLMSwingAgent
        |
        v
5. Select model
        |
        v
6. Select workspace
        |
        v
7. Attach requirements/SOW/source files
        |
        v
8. Ask LLM to create/update code
        |
        v
9. Watch streaming response
        |
        v
10. Files are generated locally
```

---

# 28. Example: Generate a Java Project

Attach:

```text
requirements.docx
api-specification.pdf
existing-code.zip
```

Prompt:

```text
Analyze all attached files.

Create a Java 17 Spring Boot implementation.

Generate all required source files, configuration files,
tests and README.

Write the generated files to the workspace.
```

The LLM can use the extracted document context and generate the project.

---

# 29. Example: Code Review

Attach:

```text
src.zip
pom.xml
architecture.docx
```

Prompt:

```text
Review the attached project against the architecture document.

Identify:
1. Design problems
2. Security issues
3. Performance issues
4. Missing error handling
5. Threading issues
6. Database problems

Then propose fixes.
```

---

# 30. Example: Modify Existing Project

Attach:

```text
CustomerService.java
CustomerController.java
CustomerRepository.java
```

Prompt:

```text
Add pagination to the customer API.

Update all affected files.

Keep the existing coding style.

Generate the complete updated files.
```

---

# 31. Recommended Local Models

For coding tasks, use a model specifically trained for code.

Examples include coding variants of:

```text
Qwen Coder
DeepSeek Coder
Qwen-based coding models
```

For image analysis, use a multimodal/vision-capable model.

The exact model should be selected based on:

- Available RAM
- CPU/GPU
- Model size
- Quantization
- Context length
- Vision support

---

# 32. Troubleshooting

## Application waits forever

Check that LM Studio's server is running.

Test:

```powershell
Invoke-RestMethod http://127.0.0.1:1234/v1/models
```

You should receive the available models.

If this works, verify that the application's endpoint is:

```text
http://127.0.0.1:1234/v1
```

---

## HTTP 404

Usually means the endpoint is incorrect.

Use:

```text
http://127.0.0.1:1234/v1
```

not:

```text
http://127.0.0.1:1234
```

---

## HTTP 400

Check:

- Model name
- Context size
- Maximum tokens
- Supported request format

---

## No streaming output

Confirm the local server supports:

```json
"stream": true
```

LM Studio's OpenAI-compatible API normally supports streaming.

---

## Model does not understand images

The model is probably text-only.

Load a vision-capable model.

---

## PDF text is missing

Install Poppler and ensure:

```text
pdftotext
```

is available in PATH.

For scanned documents, install/configure OCR.

---

# 33. Security Considerations

This application is intended primarily for a local workstation.

The local LLM server is normally bound to:

```text
127.0.0.1
```

Do not expose the LLM API to an untrusted network without adding authentication and network controls.

Be careful when attaching confidential files.

Even though the LLM is local, the application sends attached content to the configured local LLM server.

---

# 34. Architecture

High-level architecture:

```text
+-----------------------------+
|      Swing Desktop UI       |
|                             |
|  Chat                       |
|  Attachments                |
|  Workspace                  |
|  Progress / Cancel          |
+-------------+---------------+
              |
              v
+-----------------------------+
|   LocalLLMSwingAgent        |
|                             |
|  Conversation Manager       |
|  Attachment Manager         |
|  Document Extractor         |
|  Context Limiter            |
|  Streaming HTTP Client      |
|  File Generation Manager    |
|  Workspace Manager          |
+-------------+---------------+
              |
              v
+-----------------------------+
| OpenAI-Compatible Local API |
+-------------+---------------+
              |
              v
+-----------------------------+
|          LM Studio          |
|                             |
|       Local LLM Model       |
+-----------------------------+
```

---

# 35. Current Limitations

The application is intentionally dependency-light and uses Java standard APIs for most functionality.

Current limitations include:

- Complex PDF extraction is best handled by Poppler.
- Scanned PDF OCR requires an OCR engine.
- Image understanding requires a vision-capable model.
- Spreadsheet extraction is focused on textual/cell content rather than full Excel rendering/formulas.
- Binary executable analysis is limited to metadata.
- Archive contents are inspected primarily through archive metadata.
- Multimodal support depends on the local server accepting OpenAI-compatible image input.
- Context limits depend on the selected local model.

---

# 36. Future Enhancements

Potential improvements include:

- Automatic OCR using Tesseract
- Better PDF parsing
- Full XLSX formula/value extraction
- PDF page rendering
- Image OCR
- Drag-and-drop project directories
- Git integration
- Git diff viewer
- File change approval before writing
- File backup/versioning
- Undo generated changes
- Search across workspace
- Workspace indexing
- RAG/vector database support
- Automatic code compilation
- Automatic test execution
- Automatic error feedback to the LLM
- Multi-agent coding workflow
- Tool/function calling
- Terminal execution with approval
- Project-aware context selection
- Token-aware attachment prioritization
- Model capability detection
- Multiple local LLM endpoints
- Ollama support
- llama.cpp server support
- LM Studio model discovery
- OpenAI-compatible server auto-detection

---

# 37. License

Add your preferred license before publishing the project.

For example:

```text
MIT License
```

or a dual-license arrangement if the project has additional licensing requirements.

---

# 38. Quick Start

```powershell
# Compile
javac --release 17 -d . .\LocalLLMSwingAgentV6.java

# Create JAR
jar --create `
    --file LocalLLMSwingAgentV6.jar `
    --main-class LocalLLMSwingAgentV6 `
    *.class

# Run
java -jar .\LocalLLMSwingAgentV6.jar
```

Configure:

```text
Endpoint:
http://127.0.0.1:1234/v1
```

Start chatting with your local LLM.

---

# Summary

LocalLLMSwingAgent is intended to provide a lightweight local alternative to cloud-based AI coding assistants.

Its core workflow is:

```text
Local Files
     |
     v
Attachments
     |
     v
Document Extraction
     |
     v
Context Management
     |
     v
Local LLM
     |
     v
Streaming Response
     |
     v
User Review
     |
     v
Local File Generation
```

The application keeps the primary development workflow on the local machine while providing a familiar chat-based interface for interacting with local LLMs.
