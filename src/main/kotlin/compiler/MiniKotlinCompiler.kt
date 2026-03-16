package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser

// our additions to the import block
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RuleContext

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    // mapping Kotlin->Java syntax equivalents as map, so we can easily transpile syntax
    private val syntaxMapper: Map<String, String> = mapOf(
        // observing MiniKotlin.g4:95 ...
        "Int" to "Integer",
        "String" to "String",
        "Boolean" to "Boolean",
        "Unit" to "Void",
        /** grammar doesn't have these, but let's be diligent
        "Byte" to "Byte",
        "Short" to "Short",
        "Long" to "Long",
        "Double" to "Double",
        "Float" to "Float",
        "Char" to "Char",
         */
    ).withDefault({ key ->
        // $key not found, using wildcard | alternatively, we can throw an exception
        "java.lang.Object"
    })

    // TODO: finish the failfast methodology, we will have to keep two sets of tables - asked and delivered, java can accept this but it is not clarified for miniKotlin
    // these are used to perform sanity checks, since a program may be syntax-valid but semantic-invalid/ unsound
    // additionally, this provides a fail-fast mechanism that also informs the user of these types of errors in miniKotlin, not generated Java
    private var encounteredFunctions: MutableSet<String> = mutableSetOf()
    private var encounteredVariables: MutableMap<String, MutableSet<String>> = mutableMapOf()

    private var nestingCounter = 0

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        val transpiledText: StringBuilder = StringBuilder()
        transpiledText
            .append("public class $className {\n")
            .append(visit(program) + "\n")
            .append("}")

        return transpiledText.toString()
    }

    override fun visitProgram(ctx: MiniKotlinParser.ProgramContext): String {
        val transpiledText = StringBuilder()

        val functions = ctx.functionDeclaration()
        for (function in functions) {
            transpiledText.append(visit(function) + "\n\n")
            nestingCounter = 0
        }
        return transpiledText.toString().trim().prependIndent("    ")
    }

    override fun visitFunctionDeclaration(ctx: MiniKotlinParser.FunctionDeclarationContext): String {
        val name = ctx.IDENTIFIER().text
        val parameters = ctx.parameterList()?.parameter() ?: emptyList<MiniKotlinParser.ParameterContext>()
        val rType = ctx.type().text

        val fBlock = ctx.block()

        // sanity check, has this function been encountered before?
        if (encounteredFunctions.contains(name)) {
            throw IllegalStateException("Function $name has already been defined, panicking...")
        }
        encounteredFunctions.add(name)

        var boxedParameters: String = ""
        val transpiledParameters: String = if (parameters.isEmpty()) {
            ""
        } else { // since we are boxing, we have to make sure to create a boxed version of the argument to pass into the function
            boxedParameters = parameters.joinToString("\n") {
                "final ${syntaxMapper[it.type().text]}[] __${it.IDENTIFIER().text} = new ${syntaxMapper[it.type().text]}[] { ${it.IDENTIFIER().text} };"
            }
            boxedParameters = "${boxedParameters.prependIndent("    ")}\n"

            parameters.joinToString(", ") {
                "${syntaxMapper[it.type().text]} ${it.IDENTIFIER().text}"
            }
        }

        val transpiledSignature: String = if (name == "main") {
            "public static void main(String[] args)"
        } else { // remember CPS, we must correct the transpiled signature to include a Continuation
            val correctedParameters: String = if (transpiledParameters == "") {
                "Continuation<${syntaxMapper[rType]}> __continuation"
            } else {
                "$transpiledParameters, Continuation<${syntaxMapper[rType]}> __continuation"
            }

            "public static void $name($correctedParameters)"
        }
        val transpiledBody = visit(fBlock)

        return "$transpiledSignature {\n$boxedParameters${transpiledBody.prependIndent("    ")}\n}"
    }

    override fun visitBlock(ctx: MiniKotlinParser.BlockContext): String {
        val statements = ctx.statement()
        if (statements.isEmpty()) {
            return ""
        }
        // let's push all block's statements onto a stack and then process them
        val statementStack: ArrayDeque<ParserRuleContext> = ArrayDeque()
        statements.forEach { statementStack.addLast(it) }
        val futureCodeList: ArrayDeque<String> = ArrayDeque()
        if (shouldPrefixStatementsWithReturn(ctx.parent as ParserRuleContext)) {
            futureCodeList.addLast("__continuation.accept(null);\nreturn;")
        }

        return processStatements(statementStack, futureCodeList)
    }

    // these are only going to be invoked in the trivial situation where we have nothing inside these of them to unravel, as such they can treat their contents as trivial for purposes of CPS
    override fun visitVariableDeclaration(ctx: MiniKotlinParser.VariableDeclarationContext): String {
        val vName = ctx.IDENTIFIER().text
        val vType = syntaxMapper[ctx.type().text]
        val vValue = visit(ctx.expression())

        return "final $vType[] __$vName = new $vType[] { $vValue };"
    }

    override fun visitVariableAssignment(ctx: MiniKotlinParser.VariableAssignmentContext): String {
        val vName = ctx.IDENTIFIER().text
        val vValue = visit(ctx.expression())
        return "__$vName[0] = $vValue;"
    }

    /** intentionally unimplemented, these shouldn't even be called or present in the call-stack at any point
     * because they will always be treated as CPS
    override fun visitReturnStatement(ctx: MiniKotlinParser.ReturnStatementContext): String {
        //
    }

    override fun visitFunctionCallExpr(ctx: MiniKotlinParser.FunctionCallExprContext): String {
        //
    }

    override fun visitIfStatement(ctx: MiniKotlinParser.IfStatementContext): String {
        //
    }

    override fun visitWhileStatement(ctx: MiniKotlinParser.WhileStatementContext): String {
        //
    }
     */

    override fun visitPrimaryExpr(ctx: MiniKotlinParser.PrimaryExprContext): String {
        val prim = visit(ctx.primary())
        return "$prim"
    }
    // CONT: after all expr options

    override fun visitNotExpr(ctx: MiniKotlinParser.NotExprContext): String {
        val arg = visit(ctx.expression())
        return "!($arg)"
    }

    override fun visitMulDivExpr(ctx: MiniKotlinParser.MulDivExprContext): String {
        val first = visit(ctx.expression(0))
        val second = visit(ctx.expression(1))
        val op = ctx.getChild(1).text

        return "($first $op $second)"
    }

    override fun visitAddSubExpr(ctx: MiniKotlinParser.AddSubExprContext): String {
        val first = visit(ctx.expression(0))
        val second = visit(ctx.expression(1))
        val op = ctx.getChild(1).text

        return "($first $op $second)"
    }

    override fun visitComparisonExpr(ctx: MiniKotlinParser.ComparisonExprContext): String {
        val first = visit(ctx.expression(0))
        val second = visit(ctx.expression(1))
        val op = ctx.getChild(1).text

        return "($first $op $second)"
    }

    override fun visitEqualityExpr(ctx: MiniKotlinParser.EqualityExprContext): String {
        val first = visit(ctx.expression(0))
        val second = visit(ctx.expression(1))
        val op = ctx.getChild(1).text

        return "($first $op $second)"
    }

    override fun visitAndExpr(ctx: MiniKotlinParser.AndExprContext): String {
        val first = visit(ctx.expression(0))
        val second = visit(ctx.expression(1))
        val op = ctx.getChild(1).text

        return "($first $op $second)"
    }

    override fun visitOrExpr(ctx: MiniKotlinParser.OrExprContext): String {
        val first = visit(ctx.expression(0))
        val second = visit(ctx.expression(1))
        val op = ctx.getChild(1).text

        return "($first $op $second)"
    }

    override fun visitParenExpr(ctx: MiniKotlinParser.ParenExprContext): String {
        val arg = visit(ctx.expression())
        return "($arg)"
    }

    // CONT from primary, most are trivial
    override fun visitIntLiteral(ctx: MiniKotlinParser.IntLiteralContext): String {
        return ctx.text
    }

    override fun visitStringLiteral(ctx: MiniKotlinParser.StringLiteralContext): String {
        return ctx.text
    }

    override fun visitBoolLiteral(ctx: MiniKotlinParser.BoolLiteralContext): String {
        return ctx.text
    }

    override fun visitIdentifierExpr(ctx: MiniKotlinParser.IdentifierExprContext): String {
        return "__${ctx.text}[0]"
    }

    override fun visitArgumentList(ctx: MiniKotlinParser.ArgumentListContext): String {
        val arguments = ctx.expression()
        if (arguments.isEmpty()) {
            return ""
        }

        return arguments.joinToString(", ") { expressionCtx -> visit(expressionCtx) }
    }

    /** Helpers */
    private fun shouldPrefixStatementsWithReturn(ctx: ParserRuleContext): Boolean {
        return when (ctx) {
            is MiniKotlinParser.FunctionDeclarationContext -> ctx.type().text == "Unit" && ctx.IDENTIFIER().text != "main"
            // these can contain a block by grammar definition, but shouldn't interact with this functionality
            is MiniKotlinParser.IfStatementContext -> false
            is MiniKotlinParser.WhileStatementContext -> false
            else -> throw IllegalStateException("Attempting to read return type of a ${ctx.javaClass.simpleName}")
        }
    }

    private fun requiresCPS(ctx: ParserRuleContext): Boolean {
        return when (ctx) {
            // explicitly as per CPS, these always require CPS
            is MiniKotlinParser.FunctionCallExprContext -> true
            is MiniKotlinParser.ReturnStatementContext -> true

            // these can contain an inner expression, but these should be treated as inherent CPS
            is MiniKotlinParser.IfStatementContext -> true
            is MiniKotlinParser.WhileStatementContext -> true

            // again, these CAN contain an inner expression, so we perform an analogous check
            is MiniKotlinParser.VariableDeclarationContext -> containsFunctionCall(ctx.expression())
            is MiniKotlinParser.VariableAssignmentContext -> containsFunctionCall(ctx.expression())

            is MiniKotlinParser.StatementContext -> {
                val child = ctx.getChild(0) as? ParserRuleContext ?: return false
                requiresCPS(child)
            }

            else -> false
        }
    }

    private fun containsFunctionCall(ctx: ParserRuleContext): Boolean {
        if (ctx is MiniKotlinParser.FunctionCallExprContext) return true
        for (child in ctx.children) {
            if (child is ParserRuleContext && containsFunctionCall(child)) return true
        }
        return false
    }

    // TODO: change the recursive logic into a stack-machine similar to visit FunctionDeclaration processing of statements
    private fun wrapInCPS(ctx: ParserRuleContext, fCode: String): String {
        return when (ctx) {
            is MiniKotlinParser.ReturnStatementContext -> {
                val expression = ctx.expression() ?: return "__continuation.accept(null);\nreturn;"

                unrollExpression(expression) { finalResult -> "__continuation.accept(${finalResult});\nreturn;"
                }
            }

            is MiniKotlinParser.FunctionCallExprContext -> {
                var functionName = ctx.IDENTIFIER().text
                // we replace any print call to Prelude's version, as that is what is provided to us
                if (functionName.contains("print")) {
                    functionName = "Prelude.println"
                }

                val arguments = visit(ctx.argumentList())
                val argCPS = "arg${nestingCounter++}"

                val correctedWrappingFutureCode = if (fCode != "") {
                    "\n${fCode.prependIndent("    ")}\n"
                } else {
                    ""
                }
                "$functionName($arguments, ($argCPS) -> {$correctedWrappingFutureCode});"
            }

            is MiniKotlinParser.VariableDeclarationContext -> {
                val vName = ctx.IDENTIFIER().text
                val vType = syntaxMapper[ctx.type().text]
                val expression = ctx.expression()

                unrollExpression(expression) { finalResult ->
                    val assignment = "final $vType[] __$vName = new $vType[] { $finalResult };"
                    if (fCode != "") {
                        "$assignment\n$fCode"
                    } else {
                        assignment
                    }
                }
            }

            is MiniKotlinParser.VariableAssignmentContext -> {
                val vName = ctx.IDENTIFIER().text
                val expression = ctx.expression()

                unrollExpression(expression) { finalResult ->
                    val assignment = "__$vName[0] = $finalResult;"
                    if (fCode != "") {
                        "$assignment\n$fCode"
                    } else {
                        assignment
                    }
                }
            }

            is MiniKotlinParser.IfStatementContext -> {
                unrollExpression(ctx.expression()) { condition ->
                    val tBranch = visitBlockWithContinuation(ctx.block(0), fCode)
                    val fBranch = if (ctx.block().size > 1) {
                        " else {\n${visitBlockWithContinuation(ctx.block(1), fCode).prependIndent("    ")}\n}"
                    } else {
                        " else {\n${fCode.prependIndent("    ")}\n}"
                    }

                    "if ($condition) {\n${tBranch.prependIndent("    ")}\n}$fBranch"
                }
            }

            is MiniKotlinParser.WhileStatementContext -> {
                unrollExpression(ctx.expression()) { condition ->
                    val bodyWithReentry = visitBlockWithContinuation(ctx.block(), "this.loop();")

                    constructWhileString(condition, bodyWithReentry, fCode)
                }
            }

            else -> throw IllegalStateException("Unsupported context type for CPS wrapping: ${ctx.javaClass.simpleName}")
        }
    }

    private fun unrollExpression(ctx: RuleContext, onComplete: (String) -> String): String {
        if (ctx is ParserRuleContext && !containsFunctionCall(ctx)) {
            return onComplete(visit(ctx))
        }

        if (ctx is MiniKotlinParser.FunctionCallExprContext) {
            var functionName = ctx.IDENTIFIER().text
            if (functionName.contains("print")) {
                functionName = "Prelude.println"
            }

            val arguments = visit(ctx.argumentList())
            val argCPS = "arg${nestingCounter++}"

            return "$functionName($arguments, ($argCPS) -> {\n${onComplete(argCPS).prependIndent("    ")}\n});"
        }

        // handling binary operators, which have three children at this level (not counting their own potential nesting)
        if (ctx.childCount == 3) {
            // it shouldn't matter from which side we go (left->right | right->left), since the inner workings of AST creation have decided already how they chain
            val lOperand = ctx.getChild(0) as RuleContext
            val op = ctx.getChild(1).text
            val rOperand = ctx.getChild(2) as RuleContext

            if (op == "&&") {
                return unrollExpression(lOperand) { leftRes ->
                    val argCPS = "arg${nestingCounter++}"
                    "if ($leftRes) {\n${unrollExpression(rOperand) { rightRes -> onComplete(rightRes) }.prependIndent("    ")}\n} else {\n${onComplete("false").prependIndent("    ")}\n}"
                }
            }

            if (op == "||") {
                return unrollExpression(lOperand) { leftRes ->
                    val argCPS = "arg${nestingCounter++}"
                    "if ($leftRes) {\n${onComplete("true").prependIndent("    ")}\n} else {\n${unrollExpression(rOperand) { rightRes -> onComplete(rightRes) }.prependIndent("    ")}\n}"
                }
            }

            return unrollExpression(lOperand) { leftRes ->
                unrollExpression(rOperand) { rightRes ->
                    onComplete("($leftRes $op $rightRes)")
                }
            }
        }

        return onComplete(visit(ctx))
    }

    private fun constructWhileString(condition: String, whileIfBody: String, whileElseBody: String): String {
        // staying compliant to our current string building pattern, we build our transpiled while in steps, aiming for the following
        /**
        new Object() {
            public void loop() {
                if ($condition) {
                    $bodyWithReentry
                } else {
                    $fCode
                }
            }
        }.loop();
         */

        val loopBody = "if ($condition) {\n${whileIfBody.prependIndent("    ")}\n} else {\n${whileElseBody.prependIndent("    ")}\n}"
        val loopDefinition = "public final void loop() {\n${loopBody.prependIndent("    ")}\n}"
        val completedLoopObject = "new Object() {\n${loopDefinition.prependIndent("    ")}\n}.loop();"

        return completedLoopObject
    }

    // to not change the overridable implementation of the visitBlock(),
    // we make an alternative for cases that require block processing which accepts a future, for example processing blocks inside if-else and while
    private fun visitBlockWithContinuation(ctx: MiniKotlinParser.BlockContext, tailCode: String): String {
        val statements = ctx.statement()
        if (statements.isEmpty()) {
            return tailCode
        }
        val statementStack: ArrayDeque<ParserRuleContext> = ArrayDeque()
        statements.forEach { statementStack.addLast(it) }
        val futureCodeList: ArrayDeque<String> = ArrayDeque()
        futureCodeList.addLast(tailCode)

        return processStatements(statementStack, futureCodeList)
    }

    private fun processStatements(statementStack: ArrayDeque<ParserRuleContext>, futureCodeList: ArrayDeque<String>): String {
        while (statementStack.isNotEmpty()) {
            val statement = statementStack.removeLast()
            val actualStatement = if (statement is MiniKotlinParser.StatementContext) {
                statement.getChild(0) as ParserRuleContext
            } else {
                statement
            }

            if (requiresCPS(actualStatement)) {
                val joinedFuture = futureCodeList.joinToString("\n")
                val wrapped = wrapInCPS(actualStatement, joinedFuture)
                futureCodeList.clear()
                futureCodeList.addLast(wrapped)
            } else {
                futureCodeList.addFirst(visit(actualStatement))
            }
        }

        return futureCodeList.joinToString("\n")
    }
}
