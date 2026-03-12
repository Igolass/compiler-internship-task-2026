package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser

// our additions to the import block
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RuleContext
import java.io.File

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
        println("$key not found, using wildcard")
        "java.lang.Object"
    })

    // these are used to perform sanity checks, since a program may be syntax-valid but semantic-invalid/ unsound
    // additionally, this provides a fail-fast mechanism that also informs the user of these types of errors in miniKotlin, not generated Java
    private var encounteredFunctions: MutableSet<String> = mutableSetOf()
    private var encounteredVariables: MutableMap<String, MutableSet<String>> = mutableMapOf()

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        // Our main component, we place the transpiled code into StringBuilder to get our output
        val transpiled_text: StringBuilder = StringBuilder()
        transpiled_text
            .append("public class $className {\n")
            .append(visit(program) + "\n")
            .append("}")

        return transpiled_text.toString()
    }


    override fun visitProgram(ctx: MiniKotlinParser.ProgramContext): String {
        val transpiledText = StringBuilder()

        // a program is defined as a non-zero series of functions - hence we isolate and parse them individually, their parsing will not interact with each other
        val functions = ctx.functionDeclaration()
        for (function in functions) {
            transpiledText.append(visit(function) + "\n\n")
        }
        return transpiledText.toString()
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

        val transpiledParameters: String = if (parameters.isEmpty()) {
            ""
        } else {
            parameters.joinToString(", ") {
                "${syntaxMapper[it.type().text]} ${it.IDENTIFIER().text}"
            }
        }

        val transpiledSignature: String = if (name == "main") {
            "public static void main(String[] args)"
        } else {
            // remember CPS, we must correct the transpiled signature to include a Continuation
            val correctedParameters: String = if (transpiledParameters == "") {
                "Continuation<${syntaxMapper[rType]}> __continuation"
            } else {
                "$transpiledParameters, Continuation<${syntaxMapper[rType]}> __continuation"
            }
            "public static void $name($correctedParameters)"
        }
        val transpiledBody = visit(fBlock)

        return """
        $transpiledSignature {
            $transpiledBody
        }
        """.trimIndent()
    }

    override fun visitBlock(ctx: MiniKotlinParser.BlockContext): String {
        val statements = ctx.statement()
        // similar to parameters, grammar guarantees that there is at least one statement but diligence
        if (statements.isEmpty()) {
            return ""
        }
        // let's push all block's statements onto a stack and then process them
        val statementStack: ArrayDeque<ParserRuleContext> = ArrayDeque()
        statements.forEach { statementStack.addLast(it) }

        // as we transpile backwards, we keep track of transpiled code
        val futureCodeList: ArrayDeque<String> = ArrayDeque()
        // we must account for the case of a :Unit function that is full of trivial expressions and has no return, for these purposes we inject a dummy return at the end
        if (shouldPrefixStatementsWithReturn(ctx.parent as ParserRuleContext)) {
            futureCodeList.addLast("return")
        }

        while (statementStack.isNotEmpty()) {
            // pop statement, get its actual ruleContext since that is how ANTLR works with such rules
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
                // we do not require CPS, so transpile this lucky piece of code directly and prepend it
                // these overridden invocations begin at :+8
                futureCodeList.addFirst(visit(actualStatement))
            }
        }

        return futureCodeList.joinToString("\n")
    }

    // these are only going to be invoked in the trivial situation where we have nothing inside these of them to unravel, as such they can treat their contents as trivial for purposes of CPS
    override fun visitVariableDeclaration(ctx: MiniKotlinParser.VariableDeclarationContext): String {
        val vName = ctx.IDENTIFIER().text
        val vType = syntaxMapper[ctx.type().text]
        val vValue = visit(ctx.expression())

        return "$vType $vName = $vValue;"
    }

    override fun visitVariableAssignment(ctx: MiniKotlinParser.VariableAssignmentContext): String {
        val vName = ctx.IDENTIFIER().text
        val vValue = visit(ctx.expression())
        return "$vName = $vValue;"
    }

    /**
    override fun visitIfStatement(ctx: MiniKotlinParser.IfStatementContext): String {
        //
    }

    override fun visitWhileStatement(ctx: MiniKotlinParser.WhileStatementContext): String {
        //
    }
    */

    // TODO: we actually need these for the non-nested expressions, uncomment and implement so they can be called by :137
    /** intentionally unimplemented, these shouldn't even be called or present in the call-stack at any point
    override fun visitStatement(ctx: MiniKotlinParser.StatementContext): String {
    //
    }

    override fun visitReturnStatement(ctx: MiniKotlinParser.ReturnStatementContext): String {
    val expr = ctx.expression()
    }

    override fun visitFunctionCallExpr(ctx: MiniKotlinParser.FunctionCallExprContext): String {
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
        return ctx.text
    }

    override fun visitArgumentList(ctx: MiniKotlinParser.ArgumentListContext): String {
        val arguments = ctx.expression()
        if (arguments.isEmpty()) {
            return ""
        }

        return arguments.joinToString(", ") { expressionCtx -> visit(expressionCtx) }
    }

    /** Helpers */
    // we are looking back here, which I am not a fan of, but this is the cleanest stateless way to achieve the desired functionality
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

    private var nestingCounter = 0
    private fun wrapInCPS(ctx: ParserRuleContext, fCode: String): String {
        return when (ctx) {
            is MiniKotlinParser.ReturnStatementContext -> {
                val expression = ctx.expression() ?: return "__continuation.accept(null);\nreturn;"

                unrollExpression(expression) { finalResult -> "__continuation.accept(${finalResult});\nreturn;" }
            }

            is MiniKotlinParser.FunctionCallExprContext -> {
                var functionName = ctx.IDENTIFIER().text
                // we replace any print call to Prelude's version, as that is what is provided to us
                if (functionName.contains("print")) {
                    functionName = "Prelude.println"
                }

                val arguments = visit(ctx.argumentList())
                val argCPS = "arg${nestingCounter++}"

                """
                $functionName($arguments, ($argCPS) -> {
                    $fCode
                });
                """.trimIndent()
            }

            is MiniKotlinParser.VariableDeclarationContext -> {
                val vName = ctx.IDENTIFIER().text

                val callExpression = ctx.expression() as MiniKotlinParser.FunctionCallExprContext
                val callFuncName = callExpression.IDENTIFIER().text
                val callArguments = visit(callExpression.argumentList())

                """
                $callFuncName($callArguments, ($vName) -> {
                    $fCode
                });
                """.trimIndent()
            }

            is MiniKotlinParser.VariableAssignmentContext -> {
                val vName = ctx.IDENTIFIER().text

                val callExpression = ctx.expression() as MiniKotlinParser.FunctionCallExprContext
                val callFuncName = callExpression.IDENTIFIER().text
                val callArguments = visit(callExpression.argumentList())

                """
                $callFuncName($callArguments, ($vName) -> {
                    $fCode
                });
                """.trimIndent()
            }

            is MiniKotlinParser.IfStatementContext -> {
                val condition = visit(ctx.expression())
                // visit the branches, false might not exist, so we have to be more careful with it
                val tBranch = visit(ctx.block(0))
                val fBranch = ctx.block(1)?.let { visit(it) } ?: "__continuation.accept(null);\nreturn;"

                """
                if ($condition) {
                    $tBranch
                } else {
                    $fBranch
                }
                """.trimIndent()
            }

            // TODO: implement this. It appears that while is a generally problematic loop for CPS, internet suggests an object-based approach
            // is MiniKotlinParser.WhileStatementContext -> {}

            else -> throw IllegalStateException("Unsupported context type for CPS wrapping: ${ctx.javaClass.simpleName}")
        }
    }

    private fun unrollExpression(ctx: RuleContext, onComplete: (String) -> String): String {
        if (ctx is ParserRuleContext && !containsFunctionCall(ctx)) {
            return onComplete(visit(ctx))
        }

        if (ctx is MiniKotlinParser.FunctionCallExprContext) {
            val funcName = ctx.IDENTIFIER().text
            val arguments = visit(ctx.argumentList())
            val argCPS = "arg${nestingCounter++}"


            return """
            $funcName($arguments, ($argCPS) -> {
                ${onComplete(argCPS)}
            });
            """.trimIndent()
        }

        // handling binary operators, which have three children at this level (not counting their own potential nesting)
        if (ctx.childCount == 3) {
            // it shouldn't matter from which side we go (left->right | right->left), since the inner workings of AST creation have decided already how they chain
            val lOperand = ctx.getChild(0) as RuleContext
            val op = ctx.getChild(1).text
            val rOperand = ctx.getChild(2) as RuleContext

            return unrollExpression(lOperand) { leftRes ->
                unrollExpression(rOperand) { rightRes ->
                    onComplete("($leftRes $op $rightRes)")
                }
            }
        }

        return onComplete(visit(ctx))
    }
}
