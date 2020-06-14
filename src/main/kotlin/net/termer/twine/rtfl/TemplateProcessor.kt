package net.termer.twine.rtfl

import com.google.common.io.Files
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.ext.sql.SQLClient
import io.vertx.kotlin.core.executeBlockingAwait
import io.vertx.kotlin.core.file.*
import io.vertx.kotlin.ext.sql.queryWithParamsAwait
import io.vertx.kotlin.ext.web.client.sendAwait
import net.termer.rtflc.producers.ProducerException
import net.termer.rtflc.runtime.RtflFunction
import net.termer.rtflc.runtime.RtflRuntime
import net.termer.rtflc.runtime.RuntimeException
import net.termer.rtflc.runtime.Scope
import net.termer.rtflc.type.*
import net.termer.twine.ServerManager.vertx
import net.termer.twine.Twine
import net.termer.twine.documents.DocumentOptions
import net.termer.twine.rtfl.Module.Companion.webClient
import net.termer.twine.rtfl.utils.closeClient
import net.termer.twine.rtfl.utils.createClient
import net.termer.twine.rtfl.utils.indexesOf
import net.termer.twine.rtfl.utils.sanitizeHTML
import net.termer.twine.utils.StringFilter
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.collections.ArrayList

class TemplateProcessor(ops: DocumentOptions, opener: String, closer: String) {
    // Template regex snippets
    private val rtflInsertPattern = Pattern.compile("=([\\s\\S]*.*[\\s\\S]*)")
    private val rtflInsertRawPattern = Pattern.compile("-([\\s\\S]*.*[\\s\\S]*)")
    private val rtflForPattern = Pattern.compile("-for\\[ *(.+) +in +(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflIfPattern = Pattern.compile("-if\\[ *(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflIfElsePattern = Pattern.compile("-if\\[ *(.+) *]([\\s\\S]*.*[\\s\\S]*)<else>([\\s\\S]*.*[\\s\\S]*)")
    private val rtflWhilePattern = Pattern.compile("-while\\[ *(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflRequirePattern = Pattern.compile("-require\\[ *(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflIncludePattern = Pattern.compile("-include\\[ *(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflReadFilePattern = Pattern.compile("-read-file\\[ *(.+) * as +(.+)* *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflWriteFilePattern = Pattern.compile("-write-file\\[ *(.+) * to +(.+)* *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflFileExistsPattern = Pattern.compile("-file-exists\\[ *(.+) +as +(.+)* *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflDeleteFilePattern = Pattern.compile("-delete-file\\[ *(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflMkdirPattern = Pattern.compile("-mkdir\\[ *(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflMoveFilePattern = Pattern.compile("-move-file\\[ *(.+) +to +(.+) * ]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflCopyFilePattern = Pattern.compile("-copy-file\\[ *(.+) +to +(.+) * ]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflReadHttpPattern = Pattern.compile("-read-http\\[ *(.+) +with +(.+) +as +(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflDbConnectPattern = Pattern.compile("-db-connect\\[ *(.+) +as +(.+) *]([\\s\\S)]*.*[\\s\\S]*)")
    private val rtflDbClosePattern = Pattern.compile("-db-close\\[ *(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflDbQueryPattern = Pattern.compile("-db-query\\[ *(.+) +on +(.+) +as +(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflTryPattern = Pattern.compile("-try([\\s\\S]*.*[\\s\\S]*)<catch *\\[ *(.+) *] *>([\\s\\S]*.*[\\s\\S]*)")
    private val rtflHashPattern = Pattern.compile("-hash\\[ *(.+) +as +(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflVerifyPattern = Pattern.compile("-verify\\[ *(.+) +against +(.+) +as +(.+) *]([\\s\\S]*.*[\\s\\S]*)")
    private val rtflBlockingPattern = Pattern.compile("-blocking([\\s\\S]*.*[\\s\\S]*)")

    // Runtime
    private val runtime = createDocumentRtflRuntime(ops)
    // RoutingContext
    private val route = ops.route()
    // Rtfl opener string
    private val openerStr = opener
    // Rtfl closer string
    private val closerStr = closer

    // List of files that have already been loaded using the require block
    private val requireds = ArrayList<String>()
    // Cache of included/required files
    private val cachedTempalates = HashMap<String, String>()

    /* Alternate constructors */
    constructor(ops: DocumentOptions): this(ops, "<?rtfl", "?>")

    /* Utility functions */
    // Clear content in local scope
    private fun clearContentInTemplateScope(scope: Scope) {
        scope.assignVar("___content___", JavaObjectWrapperType(StringBuilder()))
    }
    // Returns content in local scope
    private fun contentInTemplateScope(scope: Scope): StringBuilder {
        return scope.varValue("___content___").value() as StringBuilder
    }
    // Returns the current path this file is in
    private fun currentPath(): String {
        // Find domain
        val host = route.request().host()
        var dom = Twine.domains().byDomain(if(host.contains(":")) host.split(":").toTypedArray()[0] else host)
        if (dom == null) dom = Twine.domains().defaultDomain()

        val urlPath = route.request().path()
        var path = dom!!.directory() + if (urlPath.contains("/")) urlPath.substring(1, Math.max(urlPath.lastIndexOf("/"), 1)) else urlPath.substring(1)

        return File(path).absolutePath+'/'
    }
    // Resolves the true path of a relative path based on a provided location
    private fun resolvePath(path: String, location: String): String {
        return if(path.startsWith('/'))
            File(path).absolutePath
        else
            Files.simplifyPath(File(location+path).absolutePath)
    }
    // Returns the containing directory
    private fun resolveContainingDirectory(file: String, location: String): String {
        val path = resolvePath(if(file.endsWith('/')) file.substring(0, file.length-1) else file, location)

        return if(path.contains('/'))
            path.substring(0, path.lastIndexOf('/')+1)
        else
            ""
    }

    // Creates a template evaluation scope based on a provided scope
    private fun createDocumentRtflScope(scope: Scope, location: String): Scope {
        // Internal variables used by functions
        scope.createLocalVar("___route___", JavaObjectWrapperType(route))
        scope.createLocalVar("___content___", JavaObjectWrapperType(StringBuilder()))
        scope.createLocalVar("___loc___", StringType(location))

        return scope
    }
    // Creates a template evaluation scope
    private fun createDocumentRtflScope(location: String) = createDocumentRtflScope(Scope(runtime, HashMap(), null), location)
    // Creates a template evaluation child scope from the provided parent scope
    private fun createDocumentRtflScopeChild(parent: Scope, location: String) = createDocumentRtflScope(parent.descend(null), location)
    // Creates a runtime instance for document evaluation
    private fun createDocumentRtflRuntime(ops: DocumentOptions): RtflRuntime {
        val rt = RtflRuntime()
                .importStandard()
                .importJavaInterop()

        // Data aliases
        val globals = rt.globalVarables()
        val req = ops.route().request()
        val res = ops.route().response()

        // Find domain
        val host = req.host()
        var dom = Twine.domains().byDomain(if (host.contains(":")) host.split(":").toTypedArray()[0] else host)
        if (dom == null) dom = Twine.domains().defaultDomain()

        // Collect domain data
        val domainMap = HashMap<String, RtflType>()
        domainMap["name"] = RtflType.fromJavaType(dom.name())
        domainMap["domain"] = RtflType.fromJavaType(dom.domain())
        domainMap["directory"] = RtflType.fromJavaType(dom.directory())
        domainMap["ignore_404"] = RtflType.fromJavaType(dom.ignore404())
        domainMap["not_found"] = RtflType.fromJavaType(dom.notFound())
        domainMap["server_error"] = RtflType.fromJavaType(dom.serverError())
        domainMap["index"] = RtflType.fromJavaType(dom.index())

        // Collect params
        val params = req.params()
        val paramsRtfl = HashMap<String, RtflType>()
        for ((key, value) in params)
            paramsRtfl[key] = StringType(value)

        // Expose request data
        globals["absolute_uri"] = RtflType.fromJavaType(req.absoluteURI())
        globals["host"] = RtflType.fromJavaType(req.host())
        globals["path"] = RtflType.fromJavaType(req.path())
        globals["query"] = RtflType.fromJavaType(req.query())
        globals["uri"] = RtflType.fromJavaType(req.uri())
        globals["method"] = RtflType.fromJavaType(req.rawMethod())
        globals["scheme"] = RtflType.fromJavaType(req.scheme())
        globals["ip"] = RtflType.fromJavaType(req.connection().remoteAddress().host())
        globals["domain"] = MapType(domainMap)
        globals["params"] = MapType(paramsRtfl)

        /* Create request-specific functions */
        // Gets the current location of the executing script
        rt.functions()["get_directory"] = RtflFunction { _, _, scope -> scope.varValue("___loc___") }
        // Appends the sanitized version of the provided values to the page
        rt.functions()["append"] = RtflFunction { args, _, scope ->
            val sb = scope.varValue("___content___").value() as StringBuilder

            for(arg in args)
                sb.append(if(arg is NullType) "null" else sanitizeHTML(arg.value().toString()))

            NullType()
        }
        // Appends the raw version of the provided values to the page
        rt.functions()["append_raw"] = RtflFunction { args, _, scope ->
            val sb = scope.varValue("___content___").value() as StringBuilder

            for(arg in args)
                sb.append(if (arg is NullType) "null" else arg.value().toString())

            NullType()
        }
        // Sets the HTTP response status code
        rt.functions()["set_status_code"] = RtflFunction { args, _, _ ->
            if(args.isNotEmpty() && args[0] is IntType)
                res.statusCode = args[0].value() as Int
            else
                throw RuntimeException("Must provide status int")

            NullType()
        }
        // Returns the current HTTP response status code
        rt.functions()["get_status_code"] = RtflFunction { _, _, _ ->
            IntType(res.statusCode)
        }
        // Sets the HTTP response status message
        rt.functions()["set_status_message"] = RtflFunction { args, _, _ ->
            if (args.isNotEmpty() && args[0] is StringType)
                res.statusMessage = args[0].value() as String
            else
                throw RuntimeException("Must provide status message")

            NullType()
        }
        // Returns the HTTP response status message
        rt.functions()["get_status_message"] = RtflFunction { _, _, _ ->
            StringType(res.statusMessage)
        }
        // Clears the current page append content for this code block
        rt.functions()["clear"] = RtflFunction { _, _, scope ->
            scope.assignVar("___content___", JavaObjectWrapperType(StringBuilder()))
            NullType()
        }
        // Puts an HTTP response header
        rt.functions()["put_header"] = RtflFunction { args, _, _ ->
            if (args.size > 1 && args[0] is StringType && (args[1] is StringType || args[1] is NullType)) {
                val name = args[0].value() as String
                val value = args[1].value() as String

                res.putHeader(name, value)
            } else {
                throw RuntimeException("Must provide header name and value")
            }
            NullType()
        }
        // Returns an HTTP header's value
        rt.functions()["get_header"] = RtflFunction { args, _, _ ->
            if(args.isNotEmpty() && args[0] is StringType)
                RtflType.fromJavaType(req.getHeader(args[0].value() as String))
            else
                throw RuntimeException("Must provide header name")
        }
        // Puts a session value
        rt.functions()["put_session"] = RtflFunction { args, _, _ ->
            if(args.size > 1 && args[0] is StringType) {
                val name = args[0].value() as String
                ops.route().session().put(name, args[1].value())
            } else {
                throw RuntimeException("Must provide key name and value")
            }

            NullType()
        }
        // Returns a session value
        rt.functions()["get_session"] = RtflFunction { args, _, _ ->
            if(args.isEmpty() && args[0] is StringType)
                RtflType.fromJavaType(ops.route().session().get(args[0].value() as String))
            else
                throw RuntimeException("Must provide key name")
        }
        // Sends a file as the HTTP response
        rt.functions()["send_file"] = RtflFunction { args, _, _ ->
            if (args.isNotEmpty() && args[0] is StringType)
                res.sendFile(args[0].value() as String)
            else
                throw RuntimeException("Must provide file path")

            NullType()
        }
        // Prepares an SQL query map
        rt.functions()["query_prepare"] = RtflFunction { args, _, _ ->
            if(args.isNotEmpty() && args[0] is StringType) {
                MapType(HashMap<String, RtflType>().apply {
                    this["sql"] = args[0]

                    if(args.size > 1 && args[1] is ArrayType)
                        this["params"] = args[1]
                })
            } else {
                throw RuntimeException("SQL query and optionally an array of parameters")
            }
        }
        // Creates a DB connection info map
        rt.functions()["db_config"] = RtflFunction { args, _, _ ->
            if(args.size > 3 && args[0] is StringType && args[1] is StringType && args[2] is StringType && args[3] is IntType) {
                MapType(HashMap<String, RtflType>().apply {
                    this["url"] = args[0]
                    this["username"] = args[1]
                    this["password"] = args[2]
                    this["max_pool_size"] = args[3]
                })
            } else {
                throw RuntimeException("Must provide ")
            }
        }
        // Returns a list of all database connections and their URLs
        rt.functions()["db_connections"] = RtflFunction { _, _, _ ->
            ArrayType(ArrayList<RtflType>().apply {
                for((key, value) in Module.dbConnections.entries)
                    this.add(MapType(HashMap<String, RtflType>().apply {
                        this["url"] = StringType(key)
                        this["client"] = JavaObjectWrapperType(value)
                    }))
            })
        }
        // Gets the value of a global variable in the global runtime
        rt.functions()["get"] = RtflFunction { args, _, _ ->
            if(args.isNotEmpty() && args[0] is StringType) {
                val globals = Module.runtime.globalVarables()
                val name = args[0].value() as String
                if(globals.containsKey(name))
                    globals[name]
                else
                    throw RuntimeException("Variable \"$name\" does not exist in global runtime")
            } else {
                throw RuntimeException("Must provide name of variable to get")
            }
        }
        // Sets the value of a global variable in the global runtime, or creates one with the provided name and value
        rt.functions()["set"] = RtflFunction { args, _, _ ->
            if(args.size > 1 && args[0] is StringType) {
                val globals = Module.runtime.globalVarables()
                val name = args[0].value() as String
                globals[name] = args[1]

                NullType()
            } else {
                throw RuntimeException("Must provide name of variable to set and a value for it")
            }
        }
        // Calls a function in the global runtime and returns the result
        rt.functions()["call"] = RtflFunction { args, _, _ ->
            if(args.isNotEmpty() && args[0] is StringType) {
                val funcArgs = ArrayList<RtflType>()
                for(i in 1 until args.size)
                    funcArgs.add(args[i])

                val funcs = Module.runtime.functions()
                val name = args[0].value() as String
                if(funcs.containsKey(name)) {
                    funcs[name]?.run(funcArgs.toTypedArray(), Module.runtime, Scope(Module.runtime, HashMap(), null))
                } else {
                    throw RuntimeException("Function \"$name\" does not exist in global runtime")
                }
            } else {
                throw RuntimeException("Must provide name of function to call")
            }
        }
        // Sanitizes the provided string for HTML insertion
        rt.functions()["sanitize"] = RtflFunction { args, _, _ ->
            if(args.isNotEmpty() && args[0] is StringType)
                StringType(
                        (args[0].value() as String)
                                .replace("&", "&amp;")
                                .replace("<", "&lt;")
                                .replace(">", "&gt;")
                                .replace("\"", "&quot;")
                                .replace("'", "&#39;")
                )
            else
                throw RuntimeException("Must provide string to sanitize")
        }

        return rt
    }

    /**
     * Evaluates a template and returns the result
     * @param template The template String to evaluate
     * @return The evaluated template
     * @throws RtflRuntime If executing Rtfl code fails
     * @throws ProducerException If any Rtfl code contains a syntax error
     * @throws IOException If any I/O operation fails
     * @since 1.0
     */
    @Throws(RuntimeException::class, ProducerException::class, IOException::class)
    suspend fun evaluateTemplate(template: String) = evaluateTemplate(template, createDocumentRtflScope(currentPath()), currentPath())
    /**
     * Evaluates a template and returns the result
     * @param template The template String to evaluate
     * @param scope The scope to evaluate the template in
     * @return The evaluated template
     * @throws RtflRuntime If executing Rtfl code fails
     * @throws ProducerException If any Rtfl code contains a syntax error
     * @throws IOException If any I/O operation fails
     * @since 1.0
     */
    @Throws(RuntimeException::class, ProducerException::class, IOException::class)
    suspend fun evaluateTemplate(template: String, scope: Scope) = evaluateTemplate(template, scope, currentPath())
    /**
     * Evaluates a template in the provided scope and returns the result
     * @param template The template String to evaluate
     * @param scope The scope to evaluate the template in
     * @param location The directory this is being evaluated in
     * @return The evaluated template
     * @throws RtflRuntime If executing Rtfl code fails
     * @throws ProducerException If any Rtfl code contains a syntax error
     * @throws IOException If any I/O operation fails
     * @since 1.0
     */
    @Throws(RuntimeException::class, ProducerException::class, IOException::class)
    suspend fun evaluateTemplate(template: String, scope: Scope, loc: String): String {
        val location = if(scope.varValue("___loc___").value() as String != loc) scope.varValue("___loc___").value() as String else loc
        val fs = vertx().fileSystem()
        var contentReal = template
        val out = StringBuilder()
        while (contentReal.contains(openerStr) && contentReal.contains(closerStr)) {
            clearContentInTemplateScope(scope)
            val content = contentReal
            val startIndex = content.indexOf(openerStr)

            // Determine end index
            val openIndexes: Array<Int> = content.indexesOf(openerStr)
            val closeIndexes: Array<Int> = content.indexesOf(closerStr)
            var openersLeft = 1
            var iterator = 0
            var closerId = 0
            while (openersLeft > 0) {
                if (openIndexes.size - iterator + 1 > 0 && iterator + 1 < openIndexes.size && openIndexes[iterator + 1] < closeIndexes[closerId]) {
                    closerId++
                    openersLeft++
                }
                openersLeft--
                iterator++
            }
            val endIndex = closeIndexes[closerId]

            // Write markup before code block to output
            out.append(content, 0, startIndex)

            // Get code block contents
            val code = content.substring(startIndex + openerStr.length, if (endIndex == 0) content.length else endIndex)

            // Check which type of clause it is
            var matcher: Matcher?
            if(rtflInsertPattern.matcher(code).also { matcher = it }.matches()) { // Sanitized insert
                runtime.execute("append(" + code.substring(1) + ")", scope)
            } else if(rtflForPattern.matcher(code).also { matcher = it }.matches()) { // For loop block
                // Collect data
                val varName = matcher!!.group(1)
                val arrName = matcher!!.group(2)
                val body = matcher!!.group(3)
                val arrRef = scope.varValue(arrName)

                // Attempt to retrieve variable
                if (arrRef != null && arrRef is ArrayType) {
                    // Create temporary global for array
                    val tempArrName = StringFilter.generateString(20)
                    runtime.globalVarables()[tempArrName] = arrRef

                    // Fetch array
                    val arr = arrRef.value() as ArrayList<*>

                    // Create new scope to evaluate templates in
                    val execScope: Scope = scope.descend(null)
                    execScope.createLocalVar("___content___", JavaObjectWrapperType(StringBuilder()))

                    // Loop through array and evaluate template body
                    for (i in arr.indices)
                        out.append(evaluateTemplate("$openerStr\nlocal i = $i\nlocal $varName = $tempArrName[$i]\n$closerStr$body", execScope))

                    // Delete temporary variable
                    runtime.globalVarables().remove(tempArrName)
                } else {
                    throw RuntimeException("Must provide array to loop through")
                }
            } else if(rtflIfElsePattern.matcher(code).also { matcher = it }.matches()) { // If else block
                // Collect data
                val logic = matcher!!.group(1)
                val res = runtime.execute("return " + (if (logic.startsWith("!")) "![" else "[") + (if (logic.startsWith("!")) logic.substring(1) else logic) + "]", scope)
                val body = matcher!!.group(2)
                val elseBody = matcher!!.group(3)

                if(res is BoolType) {
                    if (res.value() as Boolean)
                        out.append(evaluateTemplate(body, createDocumentRtflScopeChild(scope, location)))
                    else
                        out.append(evaluateTemplate(elseBody, createDocumentRtflScopeChild(scope, location)))
                } else {
                    throw RuntimeException("If statement evaluated a value other than a boolean (" + (if (res is NullType) "null" else res.value().toString()) + ")")
                }
            } else if(rtflIfPattern.matcher(code).also { matcher = it }.matches()) { // If block
                // Collect data
                val logic = matcher!!.group(1)
                val res = runtime.execute("return " + (if (logic.startsWith("!")) "![" else "[") + (if (logic.startsWith("!")) logic.substring(1) else logic) + "]", scope)
                val body = matcher!!.group(2)

                if(res is BoolType) {
                    if (res.value() as Boolean)
                        out.append(evaluateTemplate(body, createDocumentRtflScopeChild(scope, location)))
                } else {
                    throw RuntimeException("If statement evaluated a value other than a boolean (" + (if (res is NullType) "null" else res.value().toString()) + ")")
                }
            } else if(rtflWhilePattern.matcher(code).also { matcher = it }.matches()) {
                // Collect data
                val logic = matcher!!.group(1)
                val body = matcher!!.group(2)

                while(true) {
                    val res = runtime.execute("return " + (if (logic.startsWith("!")) "![" else "[") + (if (logic.startsWith("!")) logic.substring(1) else logic) + "]", scope)

                    if(res is BoolType) {
                        if(res.value() as Boolean)
                            out.append(evaluateTemplate(body, createDocumentRtflScopeChild(scope, location)))
                        else
                            break
                    } else {
                        throw RuntimeException("While statement evaluated a value other than a boolean (" + (if (res is NullType) "null" else res.value().toString()) + ")")
                    }
                }
            } else if(rtflRequirePattern.matcher(code).also { matcher = it }.matches()) { // Require block
                // Collect data
                val filename = matcher!!.group(1)
                val nameValue = runtime.execute("return $filename", scope)

                if(nameValue is StringType) {
                    val pathArg = nameValue.value() as String
                    val file = File(resolvePath(pathArg, location))

                    // Check if the file has already been required
                    if(!requireds.contains(file.absolutePath)) {
                        // Read file or use cached version
                        val content = if(cachedTempalates.containsKey(file.absolutePath))
                            cachedTempalates[file.absolutePath]!!
                        else
                            fs.readFileAwait(file.absolutePath).toString(Charset.defaultCharset()).also {
                                cachedTempalates[file.absolutePath] = it
                            }

                        // Evaluate and append file
                        val newLoc = resolveContainingDirectory(pathArg, location)
                        out.append(evaluateTemplate(content, createDocumentRtflScopeChild(scope, newLoc), newLoc))

                        // Add to requireds list
                        requireds.add(file.absolutePath)
                    }
                } else {
                    throw RuntimeException("Must provide string value as file path to require")
                }
            } else if(rtflIncludePattern.matcher(code).also { matcher = it }.matches()) { // Include block
                // Collect data
                val filename = matcher!!.group(1)
                val nameValue = runtime.execute("return $filename", scope)

                if(nameValue is StringType) {
                    val pathArg = nameValue.value() as String
                    val file = File(resolvePath(pathArg, location))

                    // Read file or use cached version
                    val content = if(cachedTempalates.containsKey(file.absolutePath))
                        cachedTempalates[file.absolutePath]!!
                    else
                        fs.readFileAwait(resolvePath(pathArg, location)).toString(Charset.defaultCharset()).also {
                            cachedTempalates[file.absolutePath] = it
                        }

                    // Evaluate and append file
                    val newLoc = resolveContainingDirectory(pathArg, location)
                    out.append(evaluateTemplate(content, createDocumentRtflScopeChild(scope, newLoc), newLoc))
                } else {
                    throw RuntimeException("Must provide string value as file path to require")
                }
            } else if(rtflReadFilePattern.matcher(code).also { matcher = it }.matches()) { // Read file block
                // Collect data
                val filename = matcher!!.group(1)
                val varName = matcher!!.group(2)
                val nameValue = runtime.execute("return $filename", scope)

                if(nameValue is StringType) {
                    val path = resolvePath(nameValue.value() as String, location)
                    val content = fs.readFileAwait(path).toString(Charset.defaultCharset())

                    scope.createLocalVar(varName, StringType(content))
                } else {
                    throw RuntimeException("Must provide string value as file path to read")
                }
            } else if(rtflWriteFilePattern.matcher(code).also { matcher = it }.matches()) { // Write file block
                // Collect data
                val filename = matcher!!.group(2)
                val content = matcher!!.group(1)
                val nameValue = runtime.execute("return $filename", scope)
                val contentValue = runtime.execute("return $content.to_string", scope)

                if(nameValue is StringType) {
                    val path = resolvePath(nameValue.value() as String, location)

                    fs.writeFileAwait(path, Buffer.buffer(contentValue.value() as String))
                } else {
                    throw RuntimeException("Must provide string value as file path to write")
                }
            } else if(rtflFileExistsPattern.matcher(code).also { matcher = it }.matches()) { // File exists block
                // Collect data
                val filename = matcher!!.group(1)
                val varName = matcher!!.group(2)
                val nameValue = runtime.execute("return $filename", scope)

                if(nameValue is StringType) {
                    val path = resolvePath(nameValue.value() as String, location)

                    scope.createLocalVar(varName, BoolType(fs.existsAwait(path)))
                } else {
                    throw RuntimeException("Must provide string value as file path to check")
                }
            } else if(rtflDeleteFilePattern.matcher(code).also { matcher = it }.matches()) { // Delete file block
                // Collect data
                val filename = matcher!!.group(1)
                val nameValue = runtime.execute("return $filename", scope)

                if (nameValue is StringType) {
                    val path = resolvePath(nameValue.value() as String, location)

                    fs.deleteRecursiveAwait(path, true)
                } else {
                    throw RuntimeException("Must provide string value as file path to delete")
                }
            } else if(rtflMkdirPattern.matcher(code).also { matcher = it }.matches()) { // Mkdir block
                // Collect data
                val dirname = matcher!!.group(1)
                val nameValue = runtime.execute("return $dirname", scope)

                if (nameValue is StringType) {
                    val path = resolvePath(nameValue.value() as String, location)

                    fs.mkdirsAwait(path)
                } else {
                    throw RuntimeException("Must provide string value as directory to create")
                }
            } else if(rtflCopyFilePattern.matcher(code).also { matcher = it }.matches()) { // Copy file block
                // Collect data
                val filename = matcher!!.group(1)
                val nameValue = runtime.execute("return $filename", scope)
                val newName = matcher!!.group(2)
                val newValue = runtime.execute("return $newName", scope)

                if (nameValue is StringType && newValue is StringType) {
                    val path = resolvePath(nameValue.value() as String, location)
                    val newPath = resolvePath(newValue.value() as String, location)

                    fs.copyRecursiveAwait(path, newPath, true)
                } else {
                    throw RuntimeException("Must provide string value as file path to copy and new file location")
                }
            } else if(rtflMoveFilePattern.matcher(code).also { matcher = it }.matches()) { // Move file block
                // Collect data
                val filename = matcher!!.group(1)
                val nameValue = runtime.execute("return $filename", scope)
                val newName = matcher!!.group(2)
                val newValue = runtime.execute("return $newName", scope)

                if (nameValue is StringType && newValue is StringType) {
                    val path = resolvePath(nameValue.value() as String, location)
                    val newPath = resolvePath(newValue.value() as String, location)

                    fs.moveAwait(path, newPath)
                } else {
                    throw RuntimeException("Must provide string value as file path to move and new file location")
                }
            } else if(rtflReadHttpPattern.matcher(code).also { matcher = it }.matches()) { // Read HTTP block
                // Collect data
                val urlStr = matcher!!.group(1)
                val urlVal = runtime.execute("return $urlStr", scope)
                val opsStr = matcher!!.group(2)
                val opsVal = runtime.execute("return $opsStr", scope)
                val varName = matcher!!.group(3)

                if (urlVal is StringType && opsVal is MapType) {
                    val url = urlVal.value() as String
                    val options = RtflType.toJavaType(opsVal) as HashMap<String, Object>

                    try {
                        var method = HttpMethod.GET
                        if (options.containsKey("method"))
                            method = HttpMethod.valueOf(options["method"].toString())
                        var query = ""
                        if (options.containsKey("params") && options["params"] is HashMap<*, *>) {
                            query += '?'
                            for ((key, value) in (options["params"] as HashMap<*, *>).entries)
                                query += "${URLEncoder.encode(key as String, "UTF-8")}=${URLEncoder.encode(value.toString(), "UTF-8")}&"
                            query = query.substring(0, query.length - 1)
                        }

                        val res = webClient.requestAbs(method, url + query).sendAwait()

                        scope.createLocalVar(varName, StringType(res.body().toString(Charset.defaultCharset())))
                    } catch (e: IOException) {
                        throw RuntimeException("Failed to fetch URL $url")
                    }
                }
            } else if(rtflDbConnectPattern.matcher(code).also { matcher = it }.matches()) { // DB connect block
                // Collect data
                val opsStr = matcher!!.group(1)
                val opsVal = runtime.execute("return $opsStr", scope)
                val varName = matcher!!.group(2)

                if (opsVal is MapType) {
                    val options = RtflType.toJavaType(opsVal) as HashMap<*, *>

                    if (
                            options.containsKey("url") &&
                            options.containsKey("username") &&
                            options.containsKey("password") &&
                            options.containsKey("max_pool_size")
                    ) {
                        val url = options["url"] as String
                        val username = options["username"] as String
                        val password = options["password"] as String
                        val maxPoolSize = options["max_pool_size"] as Int

                        scope.createLocalVar(varName, JavaObjectWrapperType(createClient(url, username, password, maxPoolSize)))
                    } else {
                        throw RuntimeException("Must provide url, username, password, and max_pool_size")
                    }
                } else {
                    throw RuntimeException("Must provide connection options map which can be generated with db_config(url, username, password, max_pool_size)")
                }
            } else if(rtflDbClosePattern.matcher(code).also { matcher = it }.matches()) { // DB close block
                // Collect data
                val opsStr = matcher!!.group(1)
                val opsVal = runtime.execute("return $opsStr", scope)

                if (opsVal is MapType) {
                    val options = RtflType.toJavaType(opsVal) as HashMap<String, Object>

                    if (options.containsKey("url")) {
                        val url = options["url"] as String

                        closeClient(url)
                    } else {
                        throw RuntimeException("Must provide url, username, password, and max_pool_size")
                    }
                } else {
                    throw RuntimeException("Must provide map with at least the connection URL in order to close it")
                }
            } else if(rtflDbQueryPattern.matcher(code).also { matcher = it }.matches()) { // DB query block
                // Collect data
                val opsStr = matcher!!.group(1)
                val opsVal = runtime.execute("return $opsStr", scope)
                val connStr = matcher!!.group(2)
                val connVal = runtime.execute("return $connStr", scope)
                val varName = matcher!!.group(3)

                if (opsVal is MapType && connVal is JavaObjectWrapperType && connVal.value() is SQLClient) {
                    val options = opsVal.value() as ConcurrentHashMap<*, *>
                    val client = connVal.value() as SQLClient

                    if (options.containsKey("sql")) {
                        val sql = (options["sql"] as RtflType).value() as String
                        val params = JsonArray()
                        if (options.containsKey("params"))
                            for (param in (options["params"] as RtflType).value() as ArrayList<*>)
                                params.add(RtflType.toJavaType(param as RtflType))

                        val res = client.queryWithParamsAwait(sql, params)

                        val rtflRows = ArrayList<RtflType>()
                        if(res?.rows != null)
                            for (row in res.rows)
                                rtflRows.add(MapType(HashMap<String, RtflType>().apply {
                                    for ((key, value) in row.map.entries)
                                        this[key] = RtflType.fromJavaType(value)
                                }))

                        scope.createLocalVar(varName, ArrayType(rtflRows))
                    } else {
                        throw RuntimeException("Must at least an \"sql\" string value")
                    }
                } else {
                    throw RuntimeException("Must provide prepared query (which can be prepared with query_prepare(sql[, params]) and client")
                }
            } else if(rtflTryPattern.matcher(code).also { matcher = it }.matches()) { // Try/catch block
                // Collect data
                val tryBody = matcher!!.group(1)
                val varName = matcher!!.group(2)
                val catchBody = matcher!!.group(3)

                try {
                    out.append(evaluateTemplate(tryBody, createDocumentRtflScopeChild(scope, location)))
                } catch (e: Exception) {
                    scope.createLocalVar(varName, StringType("${e.javaClass.name}: ${e.message}"))
                    out.append(evaluateTemplate(catchBody, createDocumentRtflScopeChild(scope, location)))
                }
            } else if(rtflHashPattern.matcher(code).also { matcher = it }.matches()) { // Hash block
                // Collect data
                val hashStr = matcher!!.group(1)
                val hashVal = runtime.execute("return $hashStr", scope)
                val varName = matcher!!.group(2)

                if (hashVal is StringType)
                    scope.createLocalVar(varName, StringType(Module.crypt.hashPassword(hashVal.value() as String)))
                else
                    throw RuntimeException("Must provide string to hash")
            } else if(rtflVerifyPattern.matcher(code).also { matcher = it }.matches()) { // Hash verify block
                // Collect data
                val checkStr = matcher!!.group(1)
                val checkVal = runtime.execute("return $checkStr", scope)
                val hashStr = matcher!!.group(2)
                val hashVal = runtime.execute("return $hashStr", scope)
                val varName = matcher!!.group(3)

                if (checkVal is StringType && hashVal is StringType)
                    scope.createLocalVar(varName, BoolType(Module.crypt.verifyPassword(checkVal.value() as String, hashVal.value() as String)!!))
                else
                    throw RuntimeException("Must provide string to hash")
            } else if(rtflBlockingPattern.matcher(code).also { matcher = it }.matches()) { // Blocking code block
                vertx().executeBlockingAwait<Unit> {
                    runtime.execute(matcher!!.group(1), scope)
                    it.complete()
                }
            } else if(rtflInsertRawPattern.matcher(code).also { matcher = it }.matches()) { // Raw insert
                runtime.execute("append_raw(" + code.substring(1) + ")", scope)
            } else {
                runtime.execute(code, scope)
            }

            // Append result to content
            out.append(contentInTemplateScope(scope))

            // Remove code
            contentReal = content.substring(endIndex + closerStr.length)
        }
        out.append(contentReal)

        // Return content
        return out.toString()
    }
}