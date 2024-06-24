package com.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class CodeExecutorLambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        LambdaLogger logger = context.getLogger();
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        try {
            String javaCode = request.getBody();
            if (javaCode == null || javaCode.isEmpty()) {
                response.setStatusCode(400);
                response.setBody("Error: No code provided");
                return response;
            }

            // Limit the size of the input code
            if (javaCode.length() > 10000) { // Arbitrary limit, adjust as needed
                response.setStatusCode(413); // Payload Too Large
                response.setBody("Error: Code too large");
                return response;
            }

            // Save the code to a temporary file
            File tempFile = saveCodeToFile(javaCode);

            // Check syntax
            String syntaxError = checkSyntax(tempFile);
            if (syntaxError != null) {
                response.setStatusCode(400);
                response.setBody("Syntax Error:\n" + syntaxError);
                return response;
            }

            // Prompt the user for input
            String userInput = promptUserForInput();

            // Execute the code with user input
            String output = executeCode(tempFile, userInput);

            response.setStatusCode(200);
            response.setBody(output);
        } catch (Exception e) {
            response.setStatusCode(500);
            response.setBody("Error: " + e.getMessage());
            logger.log("Error: " + e.getMessage());
            for (StackTraceElement element : e.getStackTrace()) {
                logger.log(element.toString());
            }
        }

        return response;
    }

    private String promptUserForInput() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.print("Enter input data: ");
        return reader.readLine();
    }

    private File saveCodeToFile(String code) throws IOException {
        Path tempDir = Files.createTempDirectory("javacode");
        File tempFile = new File(tempDir.toFile(), "Main.java"); // Fixed filename to match class name
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(code);
        }
        return tempFile;
    }

    private String checkSyntax(File file) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return "Cannot find the system Java compiler. Ensure your classpath includes tools.jar";
        }

        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        int compilationResult = compiler.run(null, null, errStream, file.getPath());
        return compilationResult == 0 ? null : errStream.toString();
    }

    private String executeCode(File file, String input) throws IOException, InterruptedException {
        // Extract the class name from the file name
        String className = file.getName().replace(".java", "");

        // Get the parent directory of the file
        Path parentDir = file.getParentFile().toPath();

        // Compile the Java code using javac
        Process compileProcess = new ProcessBuilder("javac", file.getPath())
                .redirectErrorStream(true)  // Redirect error stream to output stream
                .directory(parentDir.toFile())  // Set the directory where the command will execute
                .start();  // Start the compilation process

        // Wait for the compilation process to complete within 10 seconds
        if (!compileProcess.waitFor(10, TimeUnit.SECONDS)) {
            compileProcess.destroy();  // Destroy the process if it times out
            return "Compilation timed out";  // Return error message if compilation times out
        }

        // Get the output (both stdout and stderr) of the compilation process
        String compileOutput = getProcessOutput(compileProcess);

        // Check if compilation was successful (exit value 0), if not return compilation error
        if (compileProcess.exitValue() != 0) {
            return "Compilation Error:\n" + compileOutput;
        }

        // Execute the compiled Java program using java
        Process runProcess = new ProcessBuilder("java", "-cp", parentDir.toString(), className)
                .redirectErrorStream(true)  // Redirect error stream to output stream
                .directory(parentDir.toFile())  // Set the directory where the command will execute
                .start();  // Start the execution process

        // Provide input to the Java program if needed
        if (input != null && !input.isEmpty()) {
            // Open the output stream of the running process
            try (OutputStream stdin = runProcess.getOutputStream();
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stdin))) {
                writer.write(input);  // Write input data to the process's stdin
                writer.flush();  // Flush the stream to ensure data is sent
            }
        }

        // Wait for the execution process to complete within 10 seconds
        if (!runProcess.waitFor(10, TimeUnit.SECONDS)) {
            runProcess.destroy();  // Destroy the process if it times out
            return "Execution timed out";  // Return error message if execution times out
        }

        // Get the output (both stdout and stderr) of the execution process
        String runOutput = getProcessOutput(runProcess);

        // Check if execution was successful (exit value 0), if not return execution error
        if (runProcess.exitValue() != 0) {
            return "Execution Error:\n" + runOutput;
        }

        // Return the output generated by the Java program
        return runOutput;
    }

    private String getProcessOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            return output.toString();
        }
    }
}
