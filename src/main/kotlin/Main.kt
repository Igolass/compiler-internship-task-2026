package org.example

import MiniKotlinLexer
import MiniKotlinParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.example.compiler.CompilationResult
import org.example.compiler.ExecutionResult
import org.example.compiler.JavaRuntimeCompiler
import org.example.compiler.MiniKotlinCompiler
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

fun parseFile(path: String): MiniKotlinParser.ProgramContext {
    val input = CharStreams.fromPath(Paths.get(path))
    val lexer = MiniKotlinLexer(input)
    val tokens = CommonTokenStream(lexer)
    val parser = MiniKotlinParser(tokens)
    return parser.program()
}

fun resolveStdlibPath(): Path? {
    // 1. Check system property override
    System.getProperty("stdlib.path")?.let { path ->
        val file = File(path)
        if (file.exists()) return file.toPath()
    }

    // 2. Development: check build/stdlib directory
    val devPath = Paths.get("build", "stdlib")
    if (devPath.toFile().exists()) {
        val stdlibJar = devPath.toFile().listFiles()
            ?.firstOrNull { it.name.startsWith("stdlib") && it.name.endsWith(".jar") }
        if (stdlibJar != null) return stdlibJar.toPath()
    }

    // 3. Deployed: check sibling to current JAR
    val codeSource = object {}.javaClass.protectionDomain.codeSource
    if (codeSource != null) {
        val jarDir = File(codeSource.location.toURI()).parentFile
        val siblingJar = File(jarDir, "stdlib.jar")
        if (siblingJar.exists()) return siblingJar.toPath()

        // Also check for versioned JAR
        jarDir.listFiles()
            ?.firstOrNull { it.name.startsWith("stdlib") && it.name.endsWith(".jar") }
            ?.let { return it.toPath() }
    }

    return null
}

fun compileAndRunJava(path: String, args: Array<String> = emptyArray()): Pair<CompilationResult, ExecutionResult?> {
    val compiler = JavaRuntimeCompiler()
    val sourceFile = Paths.get(path)
    val stdlibPath = resolveStdlibPath()

    return compiler.compileAndExecute(sourceFile, stdlibPath, args)
}

fun main(args: Array<String>) {
    val inputPath = args.firstOrNull() ?: "tests/example.mini"

    // 1. Parse the .mini file
    val program = parseFile(inputPath)

    // 2. Compile to Java
    val compiler = MiniKotlinCompiler()
    val javaCode = compiler.compile(program)

    // Debug: print generated Java code
    println("=== Generated Java Code ===")
    println(javaCode)
    println("=== End Generated Code ===")

    // 3. Write to temp file
    val tempDir = Files.createTempDirectory("minikotlin")
    val javaFile = tempDir.resolve("MiniProgram.java")
    Files.writeString(javaFile, javaCode)

    // 4. Compile and execute
    val (compilationResult, executionResult) = compileAndRunJava(javaFile.toString())

    // 5. Print results
    when (compilationResult) {
        is CompilationResult.Success -> {
            when (executionResult) {
                is ExecutionResult.Success -> {
                    println("=== Generated Program Output ===")
                    print(executionResult.stdout)
                    println("=== End Generated Program Output ===")
                }
                is ExecutionResult.Failure -> System.err.println("Execution error: ${executionResult.error}")
                null -> {}
            }
        }
        is CompilationResult.Failure -> {
            compilationResult.errors.forEach { System.err.println("${it.line}:${it.column}: ${it.message}") }
        }
    }
}
