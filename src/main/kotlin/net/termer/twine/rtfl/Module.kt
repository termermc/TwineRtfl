package net.termer.twine.rtfl

import io.vertx.ext.web.client.WebClient
import io.vertx.pgclient.PgPool
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.termer.rtflc.runtime.RtflRuntime
import net.termer.twine.Events
import net.termer.twine.ServerManager.vertx
import net.termer.twine.Twine
import net.termer.twine.documents.DocumentOptions
import net.termer.twine.documents.Documents
import net.termer.twine.modules.TwineModule
import net.termer.twine.modules.TwineModule.Priority.HIGH
import net.termer.twine.rtfl.utils.Crypt
import net.termer.twine.utils.TwineEvent
import net.termer.twine.utils.files.BlockingWriter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File


class Module: TwineModule {
    companion object {
        /**
         * Module logger
         * @since 1.0.0
         */
        val logger: Logger = LoggerFactory.getLogger(Module::class.java)

        /**
         * Global runtime for module
         * @since 1.0.0
         */
        val runtime: RtflRuntime = RtflRuntime()
                .importStandard()
                .importJavaInterop()

        /**
         * WebClient instance for this module
         * @since 1.0.0
         */
        val webClient: WebClient = WebClient.create(vertx())

        /**
         * Database connections
         * @since 2.0.0
         */
        val dbConnections = HashMap<String, PgPool>()

        /**
         * Argon2 utility instance
         * @since 1.0.0
         */
        val crypt = Crypt()
    }

    override fun name() = "TwineRtfl"
    override fun twineVersion() = "2.0+"
    override fun priority() = HIGH

    override fun preinitialize() { /* Nothing to do */ }
    override fun initialize() {
        logger.info("Setting up filesystem...")
        val dir = File("rtfl/")
        if (!dir.isDirectory)
            dir.mkdirs()
        val startFile = File("rtfl/start.rtfl")
        if (!startFile.exists())
            BlockingWriter.write(startFile.path, "// Script executed when the server is started\n")
        val shutdownFile = File("rtfl/shutdown.rtfl")
        if (!shutdownFile.exists())
            BlockingWriter.write(shutdownFile.path, "// Script executed right before the server is shut down\n")
        val reloadFile = File("rtfl/reload.rtfl")
        if (!reloadFile.exists())
            BlockingWriter.write(reloadFile.path, "// Script executed when server configs are reloaded\n")

        // Fire start script
        runtime.executeFile(startFile)

        // Setup events
        Events.on(Events.Type.SERVER_STOP) { ops: TwineEvent.Options ->
            if (!ops.cancelled()) {
                try {
                    runtime.executeFile(shutdownFile)
                } catch (e: Exception) {
                    logger.error("Failed to execute server stop Rtfl script:")
                    e.printStackTrace()
                }
            }
        }
        Events.on(Events.Type.CONFIG_RELOAD) { ops: TwineEvent.Options ->
            if (!ops.cancelled()) {
                try {
                    runtime.executeFile(reloadFile)
                } catch (e: Exception) {
                    logger.error("Failed to execute server config reload Rtfl script:")
                    e.printStackTrace()
                }
            }
        }

        logger.info("Setting up document processor...")
        Documents.registerExtension("rtfm")
        Documents.registerProcessor { ops: DocumentOptions ->
            GlobalScope.launch {
                if(ops.name().endsWith(".rtfm")) {
                    ops.route().response().putHeader("Content-Type", "text/html;charset=UTF-8")
                    val content = ops.content()
                    val processor = TemplateProcessor(ops)
                    try {
                        // Write content
                        ops.content(processor.evaluateTemplate(content))
                    } catch (e: Exception) {
                        logger.error("Failed to render Rtfl template:")
                        e.printStackTrace()
                        ops.content(
                                "<!DOCTYPE html>" +
                                        "<html>" +
                                        "<head>" +
                                        "<title>Error Occurred</title>" +
                                        "</head>" +
                                        "<body>" +
                                        "<h1>Error Occurred</h1>" +
                                        "<p>Error occurred while rendering template: " + e.javaClass.name + ": " + e.message!!.replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;") + "</p>" +
                                        "</body>" +
                                        "</html>"
                        )
                    }
                }
                ops.next()
            }
        }
    }
    override fun shutdown() { /* Nothing to do */ }
}