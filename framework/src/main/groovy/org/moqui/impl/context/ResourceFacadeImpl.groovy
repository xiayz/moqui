/*
 * This software is in the public domain under CC0 1.0 Universal.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.context

import groovy.transform.CompileStatic
import org.apache.avalon.framework.configuration.DefaultConfigurationBuilder
import org.moqui.entity.EntityValue

import javax.mail.util.ByteArrayDataSource
import org.apache.fop.apps.FOUserAgent
import org.apache.fop.apps.Fop
import org.apache.fop.apps.FopFactory
import org.apache.jackrabbit.jcr2dav.Jcr2davRepositoryFactory
import org.apache.jackrabbit.rmi.repository.URLRemoteRepository

import org.codehaus.groovy.runtime.InvokerHelper

import org.moqui.impl.StupidUtilities
import org.moqui.impl.context.renderer.FtlTemplateRenderer
import org.moqui.impl.context.runner.JavaxScriptRunner
import org.moqui.impl.context.runner.XmlActionsScriptRunner

import javax.activation.DataSource
import javax.activation.MimetypesFileTypeMap
import javax.jcr.Repository
import javax.jcr.Session
import javax.jcr.SimpleCredentials
import javax.naming.InitialContext
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

import org.moqui.context.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.xml.transform.Source
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.URIResolver
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.stream.StreamSource

public class ResourceFacadeImpl implements ResourceFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ResourceFacadeImpl.class)

    protected final MimetypesFileTypeMap mimetypesFileTypeMap = new MimetypesFileTypeMap()

    protected final ExecutionContextFactoryImpl ecfi

    final FtlTemplateRenderer ftlTemplateRenderer
    final XmlActionsScriptRunner xmlActionsScriptRunner

    protected final Cache scriptGroovyExpressionCache
    protected final Cache textLocationCache
    protected final Cache resourceReferenceByLocation

    protected final Map<String, Class> resourceReferenceClasses = new HashMap()
    protected final Map<String, TemplateRenderer> templateRenderers = new HashMap()
    protected final Map<String, ScriptRunner> scriptRunners = new HashMap()
    protected final ScriptEngineManager scriptEngineManager = new ScriptEngineManager();
    protected FopFactory internalFopFactory = null


    protected final Map<String, Repository> contentRepositories = new HashMap()

    protected final ThreadLocal<Map<String, Session>> contentSessions = new ThreadLocal<Map<String, Session>>()

    ResourceFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi

        ftlTemplateRenderer = new FtlTemplateRenderer()
        ftlTemplateRenderer.init(ecfi)

        xmlActionsScriptRunner = new XmlActionsScriptRunner()
        xmlActionsScriptRunner.init(ecfi)

        textLocationCache = ecfi.getCacheFacade().getCache("resource.text.location")
        scriptGroovyExpressionCache = ecfi.getCacheFacade().getCache("resource.groovy.expression")
        resourceReferenceByLocation = ecfi.getCacheFacade().getCache("resource.reference.location")

        // Setup resource reference classes
        for (Node rrNode in ecfi.confXmlRoot."resource-facade"[0]."resource-reference") {
            try {
                Class rrClass = Thread.currentThread().getContextClassLoader().loadClass((String) rrNode."@class")
                resourceReferenceClasses.put((String) rrNode."@scheme", rrClass)
            } catch (ClassNotFoundException e) {
                logger.info("Class [${rrNode.'@class'}] not found outside of components, will retry after. (${e.toString()})")
            }
        }
        
        // now that resource references are in place, init the lib and classes directories in the components
        this.ecfi.initComponentLibAndClasses(this)

        // Try failed resource reference classes again now that component classpath resources are in place
        for (Node rrNode in ecfi.confXmlRoot."resource-facade"[0]."resource-reference") {
            if (resourceReferenceClasses.containsKey(rrNode."@scheme")) continue

            try {
                Class rrClass = Thread.currentThread().getContextClassLoader().loadClass((String) rrNode."@class")
                resourceReferenceClasses.put((String) rrNode."@scheme", rrClass)
            } catch (ClassNotFoundException e) {
                logger.warn("Class [${rrNode.'@class'}] not found even with components, skipping. (${e.toString()})")
            }
        }

        // Setup template renderers
        for (Node templateRendererNode in ecfi.confXmlRoot."resource-facade"[0]."template-renderer") {
            TemplateRenderer tr = (TemplateRenderer) Thread.currentThread().getContextClassLoader()
                    .loadClass((String) templateRendererNode."@class").newInstance()
            templateRenderers.put((String) templateRendererNode."@extension", tr.init(ecfi))
        }

        // Setup script runners
        for (Node scriptRunnerNode in ecfi.confXmlRoot."resource-facade"[0]."script-runner") {
            if (scriptRunnerNode."@class") {
                ScriptRunner sr = (ScriptRunner) Thread.currentThread().getContextClassLoader()
                        .loadClass((String) scriptRunnerNode."@class").newInstance()
                scriptRunners.put((String) scriptRunnerNode."@extension", sr.init(ecfi))
            } else if (scriptRunnerNode."@engine") {
                ScriptRunner sr = new JavaxScriptRunner((String) scriptRunnerNode."@engine").init(ecfi)
                scriptRunners.put((String) scriptRunnerNode."@extension", sr)
            } else {
                logger.error("Configured script-runner for extension [${scriptRunnerNode."@extension"}] must have either a class or engine attribute and has neither.")
            }
        }

        // Setup content repositories
        for (Node repositoryNode in ecfi.confXmlRoot."repository-list"[0]."repository") {
            try {
                if (repositoryNode."@type" == "davex") {
                    Jcr2davRepositoryFactory j2drf = new Jcr2davRepositoryFactory()
                    Repository repository = j2drf.getRepository(["org.apache.jackrabbit.spi2davex.uri":(String) repositoryNode."@location"])
                    contentRepositories.put((String) repositoryNode."@name", repository)
                } else if (repositoryNode."@type" == "rmi") {
                    Repository repository = new URLRemoteRepository((String) repositoryNode."@location")
                    contentRepositories.put((String) repositoryNode."@name", repository)
                } else if (repositoryNode."@type" == "jndi") {
                    InitialContext ic = new InitialContext()
                    Repository repository = (Repository) ic.lookup((String) repositoryNode."@location")
                    contentRepositories.put((String) repositoryNode."@name", repository)
                } else if (repositoryNode."@type" == "local") {
                    throw new IllegalArgumentException("The local type content repository is not yet supported, pending research into API support for the concept")
                }
                logger.info("Added JCR Repository [${repositoryNode."@name"}] at [${repositoryNode."@location"}], workspace [${repositoryNode."@workspace"}]")
            } catch (Exception e) {
                logger.error("Error getting JCR content repository with name [${repositoryNode."@name"}], is of type [${repositoryNode."@type"}] at location [${repositoryNode."@location"}]: ${e.toString()}")
            }
        }
    }

    void destroyAllInThread() {
        Map<String, Session> sessionMap = contentSessions.get()
        if (sessionMap) for (Session openSession in sessionMap.values()) openSession.logout()
        contentSessions.remove()
    }

    @CompileStatic
    ExecutionContextFactoryImpl getEcfi() { return ecfi }
    @CompileStatic
    Map<String, TemplateRenderer> getTemplateRenderers() { return templateRenderers }

    Repository getContentRepository(String name) { return contentRepositories.get(name) }

    /** Get the active JCR Session for the context/thread, making sure it is live, and make one if needed. */
    Session getContentRepositorySession(String name) {
        Map<String, Session> sessionMap = contentSessions.get()
        if (sessionMap == null) {
            sessionMap = new HashMap()
            contentSessions.set(sessionMap)
        }
        Session newSession = sessionMap[name]
        if (newSession != null) {
            if (newSession.isLive()) {
                return newSession
            } else {
                sessionMap.remove(name)
                // newSession = null
            }
        }

        Repository rep = contentRepositories[name]
        if (!rep) return null
        Node repositoryNode = (Node) ecfi.confXmlRoot."repository-list"[0]."repository".find({ it."@name" == name })
        SimpleCredentials credentials = new SimpleCredentials(repositoryNode."@username" ?: "anonymous",
                (repositoryNode."@password" ?: "").toCharArray())
        if (repositoryNode."@workspace") {
            newSession = rep.login(credentials, (String) repositoryNode."@workspace")
        } else {
            newSession = rep.login(credentials)
        }

        if (newSession != null) sessionMap.put(name, newSession)
        return newSession
    }

    @Override
    @CompileStatic
    ResourceReference getLocationReference(String location) {
        if (location == null) return null

        ResourceReference cachedRr = (ResourceReference) resourceReferenceByLocation.get(location)
        if (cachedRr != null) return cachedRr

        String scheme = "file"
        // Q: how to get the scheme for windows? the Java URI class doesn't like spaces, the if we look for the first ":"
        //    it may be a drive letter instead of a scheme/protocol
        // A: ignore colon if only one character before it
        if (location.indexOf(":") > 1) {
            String prefix = location.substring(0, location.indexOf(":"))
            if (!prefix.contains("/") && prefix.length() > 2) scheme = prefix
        }

        Class rrClass = resourceReferenceClasses.get(scheme)
        if (!rrClass) throw new IllegalArgumentException("Prefix (${scheme}) not supported for location [${location}]")

        ResourceReference rr = (ResourceReference) rrClass.newInstance()
        rr.init(location, ecfi)
        resourceReferenceByLocation.put(location, rr)
        return rr
    }

    @Override
    @CompileStatic
    InputStream getLocationStream(String location) {
        ResourceReference rr = getLocationReference(location)
        if (rr == null) return null
        return rr.openStream()
    }

    @Override
    @CompileStatic
    String getLocationText(String location, boolean cache) {
        if (cache && textLocationCache.containsKey(location)) return (String) textLocationCache.get(location)
        InputStream locStream = getLocationStream(location)
        if (locStream == null) logger.info("Cannot get text, no resource found at location [${location}]")
        String text = StupidUtilities.getStreamText(locStream)
        if (cache) textLocationCache.put(location, text)
        return text
    }

    @Override
    @CompileStatic
    DataSource getLocationDataSource(String location) {
        ResourceReference fileResourceRef = getLocationReference(location)

        TemplateRenderer tr = getTemplateRendererByLocation(fileResourceRef.location)

        String fileName = fileResourceRef.fileName
        // strip template extension(s) to avoid problems with trying to find content types based on them
        String fileContentType = getContentType(tr != null ? tr.stripTemplateExtension(fileName) : fileName)

        boolean isBinary = isBinaryContentType(fileContentType)

        if (isBinary) {
            return new ByteArrayDataSource(fileResourceRef.openStream(), fileContentType)
        } else {
            // not a binary object (hopefully), get the text and pass it over
            if (tr != null) {
                StringWriter sw = new StringWriter()
                tr.render(fileResourceRef.location, sw)
                return new ByteArrayDataSource(sw.toString(), fileContentType)
            } else {
                // no renderer found, just grab the text (cached) and throw it to the writer
                String text = getLocationText(fileResourceRef.location, true)
                return new ByteArrayDataSource(text, fileContentType)
            }
        }
    }

    @Override
    @CompileStatic
    void renderTemplateInCurrentContext(String location, Writer writer) { template(location, writer) }
    @Override
    @CompileStatic
    void template(String location, Writer writer) {
        TemplateRenderer tr = getTemplateRendererByLocation(location)
        if (tr != null) {
            tr.render(location, writer)
        } else {
            // no renderer found, just grab the text and throw it to the writer
            String text = getLocationText(location, true)
            if (text) writer.write(text)
        }
    }

    @CompileStatic
    TemplateRenderer getTemplateRendererByLocation(String location) {
        // match against extension for template renderer, with as many dots that match as possible (most specific match)
        int mostDots = 0
        TemplateRenderer tr = null
        for (Map.Entry<String, TemplateRenderer> trEntry in templateRenderers.entrySet()) {
            String ext = trEntry.getKey()
            if (location.endsWith(ext)) {
                int dots = StupidUtilities.countChars(ext, (char) '.')
                if (dots > mostDots) {
                    mostDots = dots
                    tr = trEntry.getValue()
                }
            }
        }
        return tr
    }

    @Override
    @CompileStatic
    Object runScriptInCurrentContext(String location, String method) { return script(location, method) }

    @Override
    @CompileStatic
    Object runScriptInCurrentContext(String location, String method, Map additionalContext) {
        return script(location, method, additionalContext)
    }

    @Override
    @CompileStatic
    Object script(String location, String method) {
        ExecutionContext ec = ecfi.getExecutionContext()
        String extension = location.substring(location.lastIndexOf("."))
        ScriptRunner sr = this.scriptRunners.get(extension)

        if (sr != null) {
            return sr.run(location, method, ec)
        } else {
            // see if the extension is known
            ScriptEngine engine = scriptEngineManager.getEngineByExtension(extension)
            if (engine == null) throw new IllegalArgumentException("Cannot run script [${location}], unknown extension (not in Moqui Conf file, and unkown to Java ScriptEngineManager).")

            return JavaxScriptRunner.bindAndRun(location, ec, engine,
                    ecfi.getCacheFacade().getCache("resource.script${extension}.location"))
        }

    }
    @Override
    @CompileStatic
    Object script(String location, String method, Map additionalContext) {
        ExecutionContext ec = ecfi.getExecutionContext()
        ContextStack cs = (ContextStack) ec.context
        try {
            if (additionalContext) {
                if (additionalContext instanceof EntityValue) cs.push(((EntityValue) additionalContext).getMap())
                else cs.push(additionalContext)
                // do another push so writes to the context don't modify the passed in Map
                cs.push()
            }
            return script(location, method)
        } finally {
            if (additionalContext) { cs.pop(); cs.pop(); }
        }
    }

    @CompileStatic
    Object setInContext(String field, String from, String value, String defaultValue, String type, String setIfEmpty) {
        def tempValue = getValueFromContext(from, value, defaultValue, type)
        ecfi.getExecutionContext().getContext().put("_tempValue", tempValue)
        if (tempValue || setIfEmpty) expression("${field} = _tempValue", "")

        return tempValue
    }
    @CompileStatic
    Object getValueFromContext(String from, String value, String defaultValue, String type) {
        def tempValue = from ? expression(from, "") : expand(value, "")
        if (!tempValue && defaultValue) tempValue = expand(defaultValue, "")
        if (type) tempValue = StupidUtilities.basicConvert(tempValue, type)
        return tempValue
    }

    @Override
    @CompileStatic
    boolean evaluateCondition(String expression, String debugLocation) { return condition(expression, debugLocation) }

    @Override
    @CompileStatic
    boolean evaluateCondition(String expression, String debugLocation, Map additionalContext) {
        return condition(expression, debugLocation, additionalContext)
    }

    @Override
    @CompileStatic
    boolean condition(String expression, String debugLocation) {
        if (!expression) return false
        try {
            Script script = getGroovyScript(expression)
            Object result = script.run()
            return result as boolean
        } catch (Exception e) {
            throw new IllegalArgumentException("Error in condition [${expression}] from [${debugLocation}]", e)
        }
    }
    @Override
    @CompileStatic
    boolean condition(String expression, String debugLocation, Map additionalContext) {
        ExecutionContext ec = ecfi.getExecutionContext()
        ContextStack cs = (ContextStack) ec.context
        try {
            if (additionalContext) {
                if (additionalContext instanceof EntityValue) cs.push(((EntityValue) additionalContext).getMap())
                else cs.push(additionalContext)
                // do another push so writes to the context don't modify the passed in Map
                cs.push()
            }
            return condition(expression, debugLocation)
        } finally {
            if (additionalContext) { cs.pop(); cs.pop(); }
        }
    }

    @Override
    @CompileStatic
    Object evaluateContextField(String expr, String debugLocation) { return expression(expr, debugLocation) }
    @Override
    @CompileStatic
    Object evaluateContextField(String expr, String debugLocation, Map additionalContext) {
        return expression(expr, debugLocation, additionalContext)
    }
    @Override
    @CompileStatic
    Object expression(String expression, String debugLocation) {
        if (!expression) return null
        try {
            Script script = getGroovyScript(expression)
            Object result = script.run()
            return result
        } catch (Exception e) {
            throw new IllegalArgumentException("Error in field expression [${expression}] from [${debugLocation}]", e)
        }
    }
    @Override
    @CompileStatic
    Object expression(String expr, String debugLocation, Map additionalContext) {
        ExecutionContext ec = ecfi.getExecutionContext()
        ContextStack cs = (ContextStack) ec.context
        try {
            if (additionalContext) {
                if (additionalContext instanceof EntityValue) cs.push(((EntityValue) additionalContext).getMap())
                else cs.push(additionalContext)
                // do another push so writes to the context don't modify the passed in Map
                cs.push()
            }
            return expression(expr, debugLocation)
        } finally {
            if (additionalContext) { cs.pop(); cs.pop(); }
        }
    }


    @Override
    @CompileStatic
    String evaluateStringExpand(String inputString, String debugLocation) { expand(inputString, debugLocation) }
    @Override
    @CompileStatic
    String evaluateStringExpand(String inputString, String debugLocation, Map additionalContext) {
        return expand(inputString, debugLocation, additionalContext)
    }
    @Override
    @CompileStatic
    String expand(String inputString, String debugLocation) {
        if (!inputString) return ""

        // always localize string before expanding
        if (inputString.length() < 256) inputString = ecfi.l10nFacade.localize(inputString)
        // if no $ then it's a plain String, just return it
        if (!inputString.contains("\$")) return inputString

        String expression = '"""' + inputString + '"""'
        try {
            Script script = getGroovyScript(expression)
            if (script == null) return null
            Object result = script.run()
            return result as String
        } catch (Exception e) {
            throw new IllegalArgumentException("Error in string expression [${expression}] from [${debugLocation}]", e)
        }
    }
    @Override
    @CompileStatic
    String expand(String inputString, String debugLocation, Map additionalContext) {
        ExecutionContext ec = ecfi.getExecutionContext()
        ContextStack cs = (ContextStack) ec.context
        boolean doPushPop = additionalContext as boolean
        try {
            if (doPushPop) {
                if (additionalContext instanceof EntityValue) { cs.push(((EntityValue) additionalContext).getMap()) }
                else { cs.push(additionalContext) }
                // do another push so writes to the context don't modify the passed in Map
                cs.push()
            }
            return expand(inputString, debugLocation)
        } finally {
            if (doPushPop) { cs.pop(); cs.pop(); }
        }
    }

    @CompileStatic
    Script getGroovyScript(String expression) {
        Class groovyClass = (Class) this.scriptGroovyExpressionCache.get(expression)
        if (groovyClass == null) {
            groovyClass = new GroovyClassLoader().parseClass(expression)
            this.scriptGroovyExpressionCache.put(expression, groovyClass)
        }
        // NOTE: consider keeping the binding somewhere, like in the ExecutionContext to avoid creating repeatedly
        Script script = InvokerHelper.createScript(groovyClass, new ContextBinding(getEcfi().getExecutionContext().getContext()))
        return script
    }

    @CompileStatic
    static String stripLocationPrefix(String location) {
        if (!location) return ""

        // first remove colon (:) and everything before it
        StringBuilder strippedLocation = new StringBuilder(location)
        int colonIndex = strippedLocation.indexOf(":")
        if (colonIndex == 0) {
            strippedLocation.deleteCharAt(0)
        } else if (colonIndex > 0) {
            strippedLocation.delete(0, colonIndex+1)
        }

        // delete all leading forward slashes
        while (strippedLocation.length() > 0 && strippedLocation.charAt(0) == (char) '/') strippedLocation.deleteCharAt(0)

        return strippedLocation.toString()
    }

    @CompileStatic
    static String getLocationPrefix(String location) {
        if (!location) return ""

        if (location.contains("://")) {
            return location.substring(0, location.indexOf(":")) + "://"
        } else if (location.contains(":")) {
            return location.substring(0, location.indexOf(":")) + ":"
        } else {
            return ""
        }
    }

    @CompileStatic
    String getContentType(String filename) {
        if (!filename || !filename.contains(".")) return null
        String type = mimetypesFileTypeMap.getContentType(filename)
        // strip any parameters, ie after the ;
        if (type.contains(";")) type = type.substring(0, type.indexOf(";"))
        return type
    }

    @CompileStatic
    static boolean isBinaryContentType(String contentType) {
        if (!contentType) return false
        if (contentType.startsWith("text/")) return false
        // aside from text/*, a few notable exceptions:
        if (contentType == "application/javascript") return false
        if (contentType == "application/rtf") return false
        if (contentType == "application/xml") return false
        if (contentType == "application/xml-dtd") return false
        return true
    }

    @CompileStatic
    void xslFoTransform(StreamSource xslFoSrc, StreamSource xsltSrc, OutputStream out, String contentType) {
        TransformerFactory factory = TransformerFactory.newInstance()
        factory.setURIResolver(new LocalResolver(ecfi, factory.getURIResolver()))

        Transformer transformer = xsltSrc == null ? factory.newTransformer() : factory.newTransformer(xsltSrc)
        transformer.setURIResolver(new LocalResolver(ecfi, transformer.getURIResolver()))

        FopFactory ff = getFopFactory()
        FOUserAgent foUserAgent = ff.newFOUserAgent()
        foUserAgent.setURIResolver(new LocalResolver(ecfi, foUserAgent.getURIResolver()))
        Fop fop = ff.newFop(contentType, foUserAgent, out)

        transformer.transform(xslFoSrc, new SAXResult(fop.getDefaultHandler()))
    }

    @CompileStatic
    FopFactory getFopFactory() {
        if (internalFopFactory != null) return internalFopFactory

        // setup FopFactory
        internalFopFactory = FopFactory.newInstance()
        // Limit the validation for backwards compatibility
        internalFopFactory.setStrictValidation(false)
        DefaultConfigurationBuilder cfgBuilder = new DefaultConfigurationBuilder()
        internalFopFactory.setUserConfig(cfgBuilder.build(ecfi.resourceFacade.getLocationStream("classpath://fop.xconf")))
        internalFopFactory.getFontManager().setFontBaseURL((String) ecfi.runtimePath + "/conf")
        internalFopFactory.setURIResolver(new LocalResolver(ecfi, internalFopFactory.getURIResolver()))

        return internalFopFactory
    }

    @CompileStatic
    static class LocalResolver implements URIResolver {
        protected ExecutionContextFactoryImpl ecfi
        protected URIResolver defaultResolver

        protected LocalResolver() {}

        public LocalResolver(ExecutionContextFactoryImpl ecfi, URIResolver defaultResolver) {
            this.ecfi = ecfi
            this.defaultResolver = defaultResolver
        }

        public Source resolve(String href, String base) {
            // try plain href
            ResourceReference rr = ecfi.getResourceFacade().getLocationReference(href)

            // if href has no colon try base + href
            if (rr == null && href.indexOf(':') < 0) rr = ecfi.getResourceFacade().getLocationReference(base + href)

            if (rr != null) {
                URL url = rr.getUrl()
                InputStream is = rr.openStream()
                if (is != null) {
                    if (url != null) {
                        return new StreamSource(is, url.toExternalForm())
                    } else {
                        return new StreamSource(is)
                    }
                }
            }

            return defaultResolver.resolve(href, base)
        }
    }
}
