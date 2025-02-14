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
import org.kie.api.KieServices
import org.kie.api.builder.KieBuilder
import org.kie.api.builder.Message
import org.kie.api.builder.ReleaseId
import org.kie.api.builder.Results
import org.kie.api.runtime.KieContainer
import org.kie.api.runtime.KieSession
import org.kie.api.runtime.StatelessKieSession
import org.moqui.context.Cache
import org.moqui.context.CacheFacade
import org.moqui.context.LoggerFacade
import org.moqui.context.ResourceFacade
import org.moqui.context.ScreenFacade
import org.moqui.context.TransactionFacade
import org.moqui.entity.EntityFacade
import org.moqui.impl.StupidWebUtilities
import org.moqui.impl.context.reference.UrlResourceReference
import org.moqui.service.ServiceFacade

import java.sql.Timestamp
import java.util.jar.JarFile

import org.moqui.BaseException
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.L10nFacade
import org.moqui.context.ResourceReference
import org.moqui.context.NotificationMessageListener
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.screen.ScreenFacadeImpl
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.StupidClassLoader
import org.moqui.impl.StupidUtilities

import org.apache.shiro.authc.credential.CredentialsMatcher
import org.apache.shiro.authc.credential.HashedCredentialsMatcher
import org.apache.shiro.crypto.hash.SimpleHash
import org.apache.shiro.config.IniSecurityManagerFactory
import org.apache.shiro.SecurityUtils
import org.apache.commons.collections.map.ListOrderedMap

import org.apache.camel.CamelContext
import org.apache.camel.impl.DefaultCamelContext
import org.moqui.impl.service.camel.MoquiServiceComponent
import org.moqui.impl.service.camel.MoquiServiceConsumer

import org.elasticsearch.node.NodeBuilder
import org.elasticsearch.client.Client

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ExecutionContextFactoryImpl implements ExecutionContextFactory {
    protected final static Logger logger = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class)
    
    protected boolean destroyed = false
    
    protected String runtimePath
    protected final String confPath
    protected final Node confXmlRoot
    protected Node serverStatsNode

    protected StupidClassLoader cachedClassLoader
    protected InetAddress localhostAddress = null

    protected final ListOrderedMap componentLocationMap = new ListOrderedMap()
    protected ThreadLocal<ExecutionContextImpl> activeContext = new ThreadLocal<ExecutionContextImpl>()
    protected Map<String, EntityFacadeImpl> entityFacadeByTenantMap = new HashMap<String, EntityFacadeImpl>()
    protected Map<String, WebappInfo> webappInfoMap = new HashMap()
    protected List<NotificationMessageListener> registeredNotificationMessageListeners = []
    protected Map<String, ArtifactStatsInfo> artifactStatsInfoByType = new HashMap<>()

    /** The SecurityManager for Apache Shiro */
    protected org.apache.shiro.mgt.SecurityManager internalSecurityManager

    /** The central object of the Camel API: CamelContext */
    protected final CamelContext camelContext
    protected MoquiServiceComponent moquiServiceComponent
    protected Map<String, MoquiServiceConsumer> camelConsumerByUriMap = new HashMap<String, MoquiServiceConsumer>()

    /* ElasticSearch fields */
    org.elasticsearch.node.Node elasticSearchNode
    Client elasticSearchClient

    /* KIE fields */
    protected final Cache kieComponentReleaseIdCache
    protected final Cache kieSessionComponentCache

    // ======== Permanent Delegated Facades ========
    protected final CacheFacadeImpl cacheFacade
    protected final LoggerFacadeImpl loggerFacade
    protected final ResourceFacadeImpl resourceFacade
    protected final ScreenFacadeImpl screenFacade
    protected final ServiceFacadeImpl serviceFacade
    protected final TransactionFacadeImpl transactionFacade
    protected final L10nFacadeImpl l10nFacade

    // Some direct-cached values for better performance
    protected String skipStatsCond
    protected Integer hitBinLengthMillis
    protected Map<String, Boolean> artifactPersistHitByType = new HashMap<String, Boolean>()
    protected Map<String, Boolean> artifactPersistBinByType = new HashMap<String, Boolean>()

    /**
     * This constructor gets runtime directory and conf file location from a properties file on the classpath so that
     * it can initialize on its own. This is the constructor to be used by the ServiceLoader in the Moqui.java file,
     * or by init methods in a servlet or context filter or OSGi component or Spring component or whatever.
     */
    ExecutionContextFactoryImpl() {
        // get the MoquiInit.properties file
        Properties moquiInitProperties = new Properties()
        URL initProps = this.class.getClassLoader().getResource("MoquiInit.properties")
        if (initProps != null) { InputStream is = initProps.openStream(); moquiInitProperties.load(is); is.close(); }

        // if there is a system property use that, otherwise from the properties file
        this.runtimePath = System.getProperty("moqui.runtime")
        if (!this.runtimePath) this.runtimePath = moquiInitProperties.getProperty("moqui.runtime")
        if (!this.runtimePath)
            throw new IllegalArgumentException("No moqui.runtime property found in MoquiInit.properties or in a system property (with: -Dmoqui.runtime=... on the command line).")

        if (this.runtimePath.endsWith("/")) this.runtimePath = this.runtimePath.substring(0, this.runtimePath.length()-1)

        // setup the runtimeFile
        File runtimeFile = new File(this.runtimePath)
        if (!runtimeFile.exists()) {
            throw new IllegalArgumentException("The moqui.runtime path [${this.runtimePath}] was not found.")
        } else {
            this.runtimePath = runtimeFile.getCanonicalPath()
        }

        // always set the full moqui.runtime system property for use in various places
        System.setProperty("moqui.runtime", this.runtimePath)

        // get the moqui configuration file path
        String confPartialPath = System.getProperty("moqui.conf")
        if (!confPartialPath) confPartialPath = moquiInitProperties.getProperty("moqui.conf")
        if (!confPartialPath)
            throw new IllegalArgumentException("No moqui.conf property found in MoquiInit.properties or in a system property (with: -Dmoqui.conf=... on the command line).")

        // setup the confFile
        if (confPartialPath.startsWith("/")) confPartialPath = confPartialPath.substring(1)
        String confFullPath = this.runtimePath + "/" + confPartialPath
        File confFile = new File(confFullPath)
        if (confFile.exists()) {
            this.confPath = confFullPath
        } else {
            this.confPath = null
            logger.warn("The moqui.conf path [${confFullPath}] was not found.")
        }

        confXmlRoot = this.initConfig()

        preFacadeInit()

        // setup the CamelContext, but don't init yet
        camelContext = new DefaultCamelContext()

        // this init order is important as some facades will use others
        this.cacheFacade = new CacheFacadeImpl(this)
        logger.info("Moqui CacheFacadeImpl Initialized")
        this.loggerFacade = new LoggerFacadeImpl(this)
        logger.info("Moqui LoggerFacadeImpl Initialized")
        this.resourceFacade = new ResourceFacadeImpl(this)
        logger.info("Moqui ResourceFacadeImpl Initialized")

        this.transactionFacade = new TransactionFacadeImpl(this)
        logger.info("Moqui TransactionFacadeImpl Initialized")
        // always init the EntityFacade for tenantId DEFAULT
        this.entityFacadeByTenantMap.put("DEFAULT", new EntityFacadeImpl(this, "DEFAULT"))
        logger.info("Moqui EntityFacadeImpl for DEFAULT Tenant Initialized")
        this.serviceFacade = new ServiceFacadeImpl(this)
        logger.info("Moqui ServiceFacadeImpl Initialized")
        this.screenFacade = new ScreenFacadeImpl(this)
        logger.info("Moqui ScreenFacadeImpl Initialized")
        this.l10nFacade = new L10nFacadeImpl(this)
        logger.info("Moqui L10nFacadeImpl Initialized")

        kieComponentReleaseIdCache = this.cacheFacade.getCache("kie.component.releaseId")
        kieSessionComponentCache = this.cacheFacade.getCache("kie.session.component")

        postFacadeInit()
    }

    /** This constructor takes the runtime directory path and conf file path directly. */
    ExecutionContextFactoryImpl(String runtimePath, String confPath) {
        // setup the runtimeFile
        File runtimeFile = new File(runtimePath)
        if (!runtimeFile.exists()) {
            throw new IllegalArgumentException("The moqui.runtime path [${runtimePath}] was not found.")
        }

        // setup the confFile
        if (runtimePath.endsWith('/')) runtimePath = runtimePath.substring(0, runtimePath.length()-1)
        if (confPath.startsWith('/')) confPath = confPath.substring(1)
        String confFullPath = runtimePath + '/' + confPath
        File confFile = new File(confFullPath)
        if (!confFile.exists()) {
            throw new IllegalArgumentException("The moqui.conf path [${confFullPath}] was not found.")
        }

        this.runtimePath = runtimePath
        this.confPath = confFullPath

        this.confXmlRoot = this.initConfig()

        preFacadeInit()

        // setup the CamelContext, but don't init yet
        camelContext = new DefaultCamelContext()

        // this init order is important as some facades will use others
        this.cacheFacade = new CacheFacadeImpl(this)
        logger.info("Moqui CacheFacadeImpl Initialized")
        this.loggerFacade = new LoggerFacadeImpl(this)
        logger.info("Moqui LoggerFacadeImpl Initialized")
        this.resourceFacade = new ResourceFacadeImpl(this)
        logger.info("Moqui ResourceFacadeImpl Initialized")

        this.transactionFacade = new TransactionFacadeImpl(this)
        logger.info("Moqui TransactionFacadeImpl Initialized")
        // always init the EntityFacade for tenantId DEFAULT
        this.entityFacadeByTenantMap.put("DEFAULT", new EntityFacadeImpl(this, "DEFAULT"))
        logger.info("Moqui EntityFacadeImpl for DEFAULT Tenant Initialized")
        this.serviceFacade = new ServiceFacadeImpl(this)
        logger.info("Moqui ServiceFacadeImpl Initialized")
        this.screenFacade = new ScreenFacadeImpl(this)
        logger.info("Moqui ScreenFacadeImpl Initialized")
        this.l10nFacade = new L10nFacadeImpl(this)
        logger.info("Moqui L10nFacadeImpl Initialized")

        kieComponentReleaseIdCache = this.cacheFacade.getCache("kie.component.releaseId")

        postFacadeInit()
    }

    @Override
    void postInit() {
        this.serviceFacade.postInit()
    }

    protected void preFacadeInit() {
        serverStatsNode = (Node) confXmlRoot.'server-stats'[0]
        skipStatsCond = serverStatsNode."@stats-skip-condition"
        hitBinLengthMillis = (serverStatsNode."@bin-length-seconds" as Integer)*1000 ?: 900000

        try {
            localhostAddress = InetAddress.getLocalHost()
        } catch (UnknownHostException e) {
            logger.warn("Could not get localhost address", new BaseException("Could not get localhost address", e))
        }

        // must load components before ClassLoader since ClassLoader currently adds lib and classes directories at init time
        initComponents()
        // init ClassLoader early so that classpath:// resources and framework interface impls will work
        initClassLoader()
    }

    protected void postFacadeInit() {
        // init ElasticSearch after facades, before Camel
        initElasticSearch()

        // everything else ready to go, init Camel
        initCamel()

        // init KIE (build modules for all components)
        initKie()

        // ========== load a few things in advance so first page hit is faster in production (in dev mode will reload anyway as caches timeout)
        // load entity defs
        this.entityFacade.loadAllEntityLocations()
        this.entityFacade.getAllEntitiesInfo(null, null, false, false)
        // load/warm framework entities
        this.entityFacade.loadFrameworkEntities()
        // init ESAPI
        StupidWebUtilities.canonicalizeValue("test")

        // now that everything is started up, if configured check all entity tables
        this.entityFacade.checkInitDatasourceTables()
        // check the moqui.server.ArtifactHit entity to avoid conflicts during hit logging; if runtime check not enabled this will do nothing
        this.entityFacade.getEntityDbMeta().checkTableRuntime(this.entityFacade.getEntityDefinition("moqui.server.ArtifactHit"))

        if (confXmlRoot."cache-list"[0]."@warm-on-start" != "false") warmCache()

        logger.info("Moqui ExecutionContextFactory Initialization Complete")
    }

    void warmCache() {
        this.entityFacade.warmCache()
        this.serviceFacade.warmCache()
        this.screenFacade.warmCache()
    }

    /** Initialize all permanent framework objects, ie those not sensitive to webapp or user context. */
    protected Node initConfig() {
        logger.info("Initializing Moqui ExecutionContextFactoryImpl\n - runtime directory [${this.runtimePath}]\n - config file [${this.confPath}]\n - moqui.runtime property [${System.getProperty("moqui.runtime")}]")

        URL defaultConfUrl = this.class.getClassLoader().getResource("MoquiDefaultConf.xml")
        if (!defaultConfUrl) throw new IllegalArgumentException("Could not find MoquiDefaultConf.xml file on the classpath")
        Node newConfigXmlRoot = new XmlParser().parse(defaultConfUrl.newInputStream())

        if (this.confPath) {
            File confFile = new File(this.confPath)
            Node overrideConfXmlRoot = new XmlParser().parse(confFile)

            // merge the active/override conf file into the default one to override any settings (they both have the same root node, go from there)
            mergeConfigNodes(newConfigXmlRoot, overrideConfXmlRoot)
        }

        return newConfigXmlRoot
    }

    protected void initComponents() {
        // init components referred to in component-list.component and component-dir elements in the conf file
        for (Node childNode in confXmlRoot."component-list"[0].children()) {
            if (childNode.name() == "component") {
                initComponent((String) childNode."@name", (String) childNode."@location")
            } else if (childNode.name() == "component-dir") {
                initComponentDir((String) childNode."@location")
            }
        }
    }

    protected void initClassLoader() {
        // now setup the CachedClassLoader, this should init in the main thread so we can set it properly
        ClassLoader pcl = (Thread.currentThread().getContextClassLoader() ?: this.class.classLoader) ?: System.classLoader
        cachedClassLoader = new StupidClassLoader(pcl)
        Thread.currentThread().setContextClassLoader(cachedClassLoader)
        // add runtime/classes jar files to the class loader
        File runtimeClassesFile = new File(runtimePath + "/classes")
        if (runtimeClassesFile.exists()) {
            cachedClassLoader.addClassesDirectory(runtimeClassesFile)
        }
        // add runtime/lib jar files to the class loader
        File runtimeLibFile = new File(runtimePath + "/lib")
        if (runtimeLibFile.exists()) {
            for (File jarFile: runtimeLibFile.listFiles()) {
                if (jarFile.getName().endsWith(".jar")) {
                    cachedClassLoader.addJarFile(new JarFile(jarFile))
                    logger.info("Added JAR from runtime/lib: ${jarFile.getName()}")
                }
            }
        }
    }

    /** this is called by the ResourceFacadeImpl constructor right after the ResourceReference classes are loaded but before ScriptRunners and TemplateRenderers */
    protected void initComponentLibAndClasses(ResourceFacadeImpl rfi) {
        // add <component>/classes and <component>/lib jar files to the class loader now that component locations loaded
        for (Map.Entry componentEntry in componentBaseLocations) {
            ResourceReference classesRr = rfi.getLocationReference((String) componentEntry.value + "/classes")
            if (classesRr.supportsExists() && classesRr.exists && classesRr.supportsDirectory() && classesRr.isDirectory()) {
                cachedClassLoader.addClassesDirectory(new File(classesRr.getUri()))
            }

            ResourceReference libRr = rfi.getLocationReference((String) componentEntry.value + "/lib")
            if (libRr.supportsExists() && libRr.exists && libRr.supportsDirectory() && libRr.isDirectory()) {
                for (ResourceReference jarRr: libRr.getDirectoryEntries()) {
                    if (jarRr.fileName.endsWith(".jar")) {
                        try {
                            cachedClassLoader.addJarFile(new JarFile(new File(jarRr.getUrl().getPath())))
                            logger.info("Added JAR from [${componentEntry.key}] component: ${jarRr.getLocation()}")
                        } catch (Exception e) {
                            logger.warn("Could not load JAR from [${componentEntry.key}] component: ${jarRr.getLocation()}: ${e.toString()}")
                        }
                    }
                }
            }
        }
    }

    synchronized void destroy() {
        if (destroyed) return

        // stop Camel to prevent more calls coming in
        if (camelContext != null) camelContext.stop()

        // stop NotificationMessageListeners
        for (NotificationMessageListener nml in registeredNotificationMessageListeners) nml.destroy()

        // stop ElasticSearch
        if (elasticSearchNode != null) elasticSearchNode.close()

        // persist any remaining bins in artifactHitBinByType
        Timestamp currentTimestamp = new Timestamp(System.currentTimeMillis())
        List<ArtifactStatsInfo> asiList = new ArrayList<>(artifactStatsInfoByType.values())
        artifactStatsInfoByType.clear()
        for (ArtifactStatsInfo asi in asiList) {
            if (asi.curHitBin == null) continue
            Map<String, Object> ahb = asi.curHitBin.makeAhbMap(this, currentTimestamp)
            executionContext.service.sync().name("create", "moqui.server.ArtifactHitBin").parameters(ahb).call()
        }

        // this destroy order is important as some use others so must be destroyed first
        if (this.serviceFacade != null) { this.serviceFacade.destroy() }
        if (this.entityFacade != null) { this.entityFacade.destroy() }
        if (this.transactionFacade != null) { this.transactionFacade.destroy() }
        if (this.cacheFacade != null) { this.cacheFacade.destroy() }

        activeContext.remove()

        destroyed = true
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!this.destroyed) {
                this.destroy()
                logger.warn("ExecutionContextFactoryImpl not destroyed, caught in finalize.")
            }
        } catch (Exception e) {
            logger.warn("Error in destroy, called in finalize of ExecutionContextFactoryImpl", e)
        }
        super.finalize()
    }

    String getRuntimePath() { return runtimePath }
    Node getConfXmlRoot() { return confXmlRoot }
    Node getServerStatsNode() { return serverStatsNode }
    Node getArtifactExecutionNode(String artifactTypeEnumId) {
        return (Node) eci.ecfi.confXmlRoot."artifact-execution-facade"[0]."artifact-execution"
                .find({ it."@type" == artifactTypeEnumId })
    }

    InetAddress getLocalhostAddress() { return localhostAddress }

    void registerNotificationMessageListener(NotificationMessageListener nml) {
        nml.init(this)
        registeredNotificationMessageListeners.add(nml)
    }
    List<NotificationMessageListener> getNotificationMessageListeners() { return registeredNotificationMessageListeners }

    @CompileStatic
    org.apache.shiro.mgt.SecurityManager getSecurityManager() {
        if (internalSecurityManager != null) return internalSecurityManager

        // init Apache Shiro; NOTE: init must be done here so that ecfi will be fully initialized and in the static context
        org.apache.shiro.util.Factory<org.apache.shiro.mgt.SecurityManager> factory =
                new IniSecurityManagerFactory("classpath:shiro.ini")
        internalSecurityManager = factory.getInstance()
        // NOTE: setting this statically just in case something uses it, but for Moqui we'll be getting the SecurityManager from the ecfi
        SecurityUtils.setSecurityManager(internalSecurityManager)

        return internalSecurityManager
    }
    @CompileStatic
    CredentialsMatcher getCredentialsMatcher(String hashType) {
        HashedCredentialsMatcher hcm = new HashedCredentialsMatcher()
        if (hashType) {
            hcm.setHashAlgorithmName(hashType)
        } else {
            hcm.setHashAlgorithmName(getPasswordHashType())
        }
        return hcm
    }
    @CompileStatic
    static String getRandomSalt() { return StupidUtilities.getRandomString(8) }
    String getPasswordHashType() {
        Node passwordNode = (Node) confXmlRoot."user-facade"[0]."password"[0]
        return passwordNode."@encrypt-hash-type" ?: "SHA-256"
    }
    @CompileStatic
    String getSimpleHash(String source, String salt) { return getSimpleHash(source, salt, getPasswordHashType()) }
    @CompileStatic
    String getSimpleHash(String source, String salt, String hashType) {
        return new SimpleHash(hashType ?: getPasswordHashType(), source, salt).toString()
    }

    // ========== Getters ==========

    @CompileStatic
    CacheFacadeImpl getCacheFacade() { return this.cacheFacade }

    @CompileStatic
    EntityFacadeImpl getEntityFacade() { return getEntityFacade(getExecutionContext().getTenantId()) }
    @CompileStatic
    EntityFacadeImpl getEntityFacade(String tenantId) {
        EntityFacadeImpl efi = this.entityFacadeByTenantMap.get(tenantId)
        if (efi == null) efi = initEntityFacade(tenantId)
        return efi
    }
    @CompileStatic
    synchronized EntityFacadeImpl initEntityFacade(String tenantId) {
        EntityFacadeImpl efi = this.entityFacadeByTenantMap.get(tenantId)
        if (efi != null) return efi

        efi = new EntityFacadeImpl(this, tenantId)
        this.entityFacadeByTenantMap.put(tenantId, efi)
        logger.info("Moqui EntityFacadeImpl for Tenant [${tenantId}] Initialized")
        return efi
    }

    @CompileStatic
    LoggerFacadeImpl getLoggerFacade() { return loggerFacade }

    @CompileStatic
    ResourceFacadeImpl getResourceFacade() { return resourceFacade }

    @CompileStatic
    ScreenFacadeImpl getScreenFacade() { return screenFacade }

    @CompileStatic
    ServiceFacadeImpl getServiceFacade() { return serviceFacade }

    @CompileStatic
    TransactionFacadeImpl getTransactionFacade() { return transactionFacade }

    @CompileStatic
    L10nFacade getL10nFacade() { return l10nFacade }

    // =============== Apache Camel Methods ===============
    @Override
    @CompileStatic
    CamelContext getCamelContext() { return camelContext }

    MoquiServiceComponent getMoquiServiceComponent() { return moquiServiceComponent }
    void registerCamelConsumer(String uri, MoquiServiceConsumer consumer) { camelConsumerByUriMap.put(uri, consumer) }
    MoquiServiceConsumer getCamelConsumer(String uri) { return camelConsumerByUriMap.get(uri) }

    protected void initCamel() {
        if (confXmlRoot."tools"[0]."@enable-camel" != "false") {
            logger.info("Starting Camel")
            moquiServiceComponent = new MoquiServiceComponent(this)
            camelContext.addComponent("moquiservice", moquiServiceComponent)
            camelContext.start()
        } else {
            logger.info("Camel disabled, not starting")
        }
    }

    // =============== ElasticSearch Methods ===============
    @Override
    @CompileStatic
    Client getElasticSearchClient() { return elasticSearchClient }

    protected void initElasticSearch() {
        // set the ElasticSearch home directory
        System.setProperty("es.path.home", runtimePath + "/elasticsearch")
        if (confXmlRoot."tools"[0]."@enable-elasticsearch" != "false") {
            logger.info("Starting ElasticSearch")
            elasticSearchNode = NodeBuilder.nodeBuilder().node()
            elasticSearchClient = elasticSearchNode.client()
        } else {
            logger.info("ElasticSearch disabled, not starting")
        }
    }


    // =============== KIE Methods ===============
    protected void initKie() {
        // if (!System.getProperty("drools.dialect.java.compiler")) System.setProperty("drools.dialect.java.compiler", "JANINO")
        if (!System.getProperty("drools.dialect.java.compiler")) System.setProperty("drools.dialect.java.compiler", "ECLIPSE")

        KieServices services = KieServices.Factory.get()
        for (String componentName in componentBaseLocations.keySet()) {
            try {
                buildKieModule(componentName, services)
            } catch (Throwable t) {
                logger.error("Error initializing KIE in component ${componentName}: ${t.toString()}", t)
            }
        }
    }

    @Override
    KieContainer getKieContainer(String componentName) {
        KieServices services = KieServices.Factory.get()

        ReleaseId releaseId = (ReleaseId) kieComponentReleaseIdCache.get(componentName)
        if (releaseId == null) releaseId = buildKieModule(componentName, services)

        if (releaseId != null) return services.newKieContainer(releaseId)
        return null
    }

    protected synchronized ReleaseId buildKieModule(String componentName, KieServices services) {
        ReleaseId releaseId = (ReleaseId) kieComponentReleaseIdCache.get(componentName)
        if (releaseId != null) return releaseId

        ResourceReference kieRr = getResourceFacade().getLocationReference("component://${componentName}/kie")
        if (!kieRr.getExists() || !kieRr.isDirectory()) {
            if (logger.isTraceEnabled()) logger.trace("No kie directory in component ${componentName}, not building KIE module.")
            return null
        }

        /*
        if (componentName == "mantle-usl") {
            SpreadsheetCompiler sc = new SpreadsheetCompiler()
            String drl = sc.compile(getResourceFacade().getLocationStream("component://mantle-usl/kie/src/main/resources/mantle/shipment/orderrate/OrderShippingDt.xls"), InputType.XLS)
            StringBuilder groovyWithLines = new StringBuilder()
            int lineNo = 1
            for (String line in drl.split("\n")) groovyWithLines.append(lineNo++).append(" : ").append(line).append("\n")
            logger.error("XLS DC as DRL: [\n${groovyWithLines}\n]")
        }
        */

        try {
            File kieDir = new File(kieRr.getUrl().getPath())
            KieBuilder builder = services.newKieBuilder(kieDir)

            // build the KIE module
            builder.buildAll()
            Results results = builder.getResults()
            if (results.hasMessages(Message.Level.ERROR)) {
                throw new BaseException("Error building KIE module in component ${componentName}: ${results.toString()}")
            } else if (results.hasMessages(Message.Level.WARNING)) {
                logger.warn("Warning building KIE module in component ${componentName}: ${results.toString()}")
            }

            findComponentKieSessions(componentName)

            // get the release ID and cache it
            releaseId = builder.getKieModule().getReleaseId()
            kieComponentReleaseIdCache.put(componentName, releaseId)

            return releaseId
        } catch (Throwable t) {
            logger.error("Error initializing KIE at ${kieRr.getLocation()}", t)
            return null
        }
    }

    protected void findAllComponentKieSessions() {
        for (String componentName in componentBaseLocations.keySet()) findComponentKieSessions(componentName)
    }
    protected void findComponentKieSessions(String componentName) {
        ResourceReference kieRr = getResourceFacade().getLocationReference("component://${componentName}/kie")
        if (!kieRr.getExists() || !kieRr.isDirectory()) return

        // get all KieBase and KieSession names and create reverse-reference Map so we know which component's
        //     module they are in, then add convenience methods to get any KieBase or KieSession by name
        ResourceReference kmoduleRr = kieRr.findChildFile("src/main/resources/META-INF/kmodule.xml")
        Node kmoduleNode = new XmlParser().parseText(kmoduleRr.getText())
        for (Node kbaseNode in kmoduleNode."kbase") {
            for (Node ksessionNode in kbaseNode."ksession") {
                String ksessionName = ksessionNode."@name"
                String existingComponentName = kieSessionComponentCache.get(ksessionName)
                if (existingComponentName) logger.warn("Found KIE session [${ksessionName}] in component [${existingComponentName}], replacing with session in component [${componentName}]")
                kieSessionComponentCache.put(ksessionName, componentName)
            }
        }

    }

    @Override
    KieSession getKieSession(String ksessionName) {
        String componentName = kieSessionComponentCache.get(ksessionName)
        // try finding all component sessions
        if (!componentName) findAllComponentKieSessions()
        componentName = kieSessionComponentCache.get(ksessionName)
        // still nothing? blow up
        if (!componentName) throw new IllegalStateException("No component KIE module found for session [${ksessionName}]")
        return getKieContainer(componentName).newKieSession(ksessionName)
    }
    @Override
    StatelessKieSession getStatelessKieSession(String ksessionName) {
        String componentName = kieSessionComponentCache.get(ksessionName)
        // try finding all component sessions
        if (!componentName) findAllComponentKieSessions()
        componentName = kieSessionComponentCache.get(ksessionName)
        // still nothing? blow up
        if (!componentName) throw new IllegalStateException("No component KIE module found for session [${ksessionName}]")
        return getKieContainer(componentName).newStatelessKieSession(ksessionName)
    }

    // ========== Interface Implementations ==========

    @Override
    @CompileStatic
    ExecutionContext getExecutionContext() { return getEci() }

    @CompileStatic
    ExecutionContextImpl getEci() {
        ExecutionContextImpl ec = this.activeContext.get()
        if (ec != null) {
            return ec
        } else {
            if (logger.traceEnabled) logger.trace("Creating new ExecutionContext in thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")
            if (!(Thread.currentThread().getContextClassLoader() instanceof StupidClassLoader))
                Thread.currentThread().setContextClassLoader(cachedClassLoader)
            ec = new ExecutionContextImpl(this)
            this.activeContext.set(ec)
            return ec
        }
    }

    void destroyActiveExecutionContext() {
        ExecutionContext ec = this.activeContext.get()
        if (ec) {
            ec.destroy()
            this.activeContext.remove()
        }
    }

    @Override
    void initComponent(String componentName, String baseLocation) throws BaseException {
        // NOTE: how to get component name? for now use last directory name
        if (baseLocation.endsWith('/')) baseLocation = baseLocation.substring(0, baseLocation.length()-1)
        int lastSlashIndex = baseLocation.lastIndexOf('/')
        if (lastSlashIndex < 0) {
            // if this happens the component directory is directly under the runtime directory, so prefix loc with that
            baseLocation = runtimePath + '/' + baseLocation
        }
        if (!componentName) componentName = baseLocation.substring(lastSlashIndex+1)

        if (componentLocationMap.containsKey(componentName))
            logger.warn("Overriding component [${componentName}] at [${componentLocationMap.get(componentName)}] with location [${baseLocation}] because another component of the same name was initialized.")
        // components registered later override those registered earlier by replacing the Map entry
        componentLocationMap.put(componentName, baseLocation)
        logger.info("Added component [${componentName}] at [${baseLocation}]")
    }

    protected void initComponentDir(String location) {
        ResourceReference componentRr = new UrlResourceReference()
        componentRr.init(location, this)
        // if directory doesn't exist skip it, runtime doesn't always have an component directory
        if (componentRr.getExists() && componentRr.isDirectory()) {
            // get all files in the directory
            TreeMap<String, ResourceReference> componentDirEntries = new TreeMap<String, ResourceReference>()
            for (ResourceReference componentSubRr in componentRr.getDirectoryEntries()) {
                // if it's a directory and doesn't start with a "." then add it as a component dir
                if (!componentSubRr.isDirectory() || componentSubRr.getFileName().startsWith(".")) continue
                componentDirEntries.put(componentSubRr.getFileName(), componentSubRr)
            }
            for (Map.Entry<String, ResourceReference> componentDirEntry in componentDirEntries) {
                this.initComponent(null, componentDirEntry.getValue().getLocation())
            }
        }
    }

    @Override
    void destroyComponent(String componentName) throws BaseException { componentLocationMap.remove(componentName) }

    @Override
    @CompileStatic
    Map<String, String> getComponentBaseLocations() {
        return Collections.unmodifiableMap(componentLocationMap)
    }

    @Override
    @CompileStatic
    L10nFacade getL10n() { getL10nFacade() }

    @Override
    @CompileStatic
    ResourceFacade getResource() { getResourceFacade() }

    @Override
    @CompileStatic
    LoggerFacade getLogger() { getLoggerFacade() }

    @Override
    @CompileStatic
    CacheFacade getCache() { getCacheFacade() }

    @Override
    @CompileStatic
    TransactionFacade getTransaction() { getTransactionFacade() }

    @Override
    @CompileStatic
    EntityFacade getEntity() { getEntityFacade(getExecutionContext()?.getTenantId()) }

    @Override
    @CompileStatic
    ServiceFacade getService() { getServiceFacade() }

    @Override
    @CompileStatic
    ScreenFacade getScreen() { getScreenFacade() }

    // ========== Server Stat Tracking ==========
    @CompileStatic
    boolean getSkipStats() {
        // NOTE: the results of this condition eval can't be cached because the expression can use any data in the ec
        ExecutionContextImpl eci = getEci()
        return skipStatsCond ? eci.resource.condition(skipStatsCond, null, [pathInfo:eci.web?.request?.pathInfo]) : false
    }

    @CompileStatic
    protected boolean artifactPersistHit(String artifactType, String artifactSubType) {
        // now checked before calling this: if ("entity".equals(artifactType)) return false
        String cacheKey = artifactType + artifactSubType
        Boolean ph = artifactPersistHitByType.get(cacheKey)
        if (ph == null) {
            Node artifactStats = getArtifactStatsNode(artifactType, artifactSubType)
            ph = 'true'.equals(artifactStats.attribute('persist-hit'))
            artifactPersistHitByType.put(cacheKey, ph)
        }
        return ph
    }
    @CompileStatic
    protected boolean artifactPersistBin(String artifactType, String artifactSubType) {
        String cacheKey = artifactType + artifactSubType
        Boolean pb = artifactPersistBinByType.get(cacheKey)
        if (pb == null) {
            Node artifactStats = getArtifactStatsNode(artifactType, artifactSubType)
            pb = 'true'.equals(artifactStats.attribute('persist-bin'))
            artifactPersistBinByType.put(cacheKey, pb)
        }
        return pb
    }

    protected Node getArtifactStatsNode(String artifactType, String artifactSubType) {
        // find artifact-stats node by type AND sub-type, if not found find by just the type
        Node artifactStats = (Node) confXmlRoot."server-stats"[0]."artifact-stats".find({ it.@type == artifactType && it."@sub-type" == artifactSubType })
        if (artifactStats == null) artifactStats = (Node) confXmlRoot."server-stats"[0]."artifact-stats".find({ it.@type == artifactType })
        return artifactStats
    }

    protected final Set<String> entitiesToSkipHitCount = new HashSet([
            'moqui.server.ArtifactHit', 'create#moqui.server.ArtifactHit',
            'moqui.server.ArtifactHitBin', 'create#moqui.server.ArtifactHitBin',
            'moqui.entity.SequenceValueItem', 'moqui.security.UserAccount', 'moqui.tenant.Tenant',
            'moqui.tenant.TenantDataSource', 'moqui.tenant.TenantDataSourceXaProp',
            'moqui.entity.document.DataDocument', 'moqui.entity.document.DataDocumentField',
            'moqui.entity.document.DataDocumentCondition', 'moqui.entity.feed.DataFeedAndDocument',
            'moqui.entity.view.DbViewEntity', 'moqui.entity.view.DbViewEntityMember',
            'moqui.entity.view.DbViewEntityKeyMap', 'moqui.entity.view.DbViewEntityAlias'])
    protected final Set<String> artifactTypesForStatsSkip = new TreeSet(["screen", "transition", "screen-content"])
    protected final long checkSlowThreshold = 20L
    protected final double userImpactMinMillis = 200

    @CompileStatic
    static class ArtifactStatsInfo {
        // put this here so we only have to do one Map lookup per countArtifactHit call
        ArtifactBinInfo curHitBin = null
        long hitCount = 0L
        long slowHitCount = 0L
        double totalTimeMillis = 0
        double totalSquaredTime = 0
        double getAverage() { return hitCount > 0 ? totalTimeMillis / hitCount : 0 }
        double getStdDev() {
            if (hitCount < 2) return 0
            return Math.sqrt(Math.abs(totalSquaredTime - ((totalTimeMillis*totalTimeMillis) / hitCount)) / (hitCount - 1L))
        }
        void incrementHitCount() { hitCount++ }
        void incrementSlowHitCount() { slowHitCount++ }
        void addRunningTime(double runningTime) {
            totalTimeMillis = totalTimeMillis + runningTime
            totalSquaredTime = totalSquaredTime + (runningTime * runningTime)
        }
    }
    @CompileStatic
    static class ArtifactBinInfo {
        String artifactType
        String artifactSubType
        String artifactName
        long startTime

        long hitCount = 0L
        long slowHitCount = 0L
        double totalTimeMillis = 0
        double totalSquaredTime = 0
        double minTimeMillis = Long.MAX_VALUE
        double maxTimeMillis = 0

        ArtifactBinInfo(String artifactType, String artifactSubType, String artifactName, long startTime) {
            this.artifactType = artifactType
            this.artifactSubType = artifactSubType
            this.artifactName = artifactName
            this.startTime = startTime
        }

        void incrementHitCount() { hitCount++ }
        void incrementSlowHitCount() { slowHitCount++ }
        void addRunningTime(double runningTime) {
            totalTimeMillis = totalTimeMillis + runningTime
            totalSquaredTime = totalSquaredTime + (runningTime * runningTime)
        }

        Map<String, Object> makeAhbMap(ExecutionContextFactoryImpl ecfi, Timestamp binEndDateTime) {
            Map<String, Object> ahb = [artifactType:artifactType, artifactSubType:artifactSubType,
                                       artifactName:artifactName, binStartDateTime:new Timestamp(startTime), binEndDateTime:binEndDateTime,
                                       hitCount:hitCount, totalTimeMillis:new BigDecimal(totalTimeMillis),
                                       totalSquaredTime:new BigDecimal(totalSquaredTime), minTimeMillis:new BigDecimal(minTimeMillis),
                                       maxTimeMillis:new BigDecimal(maxTimeMillis), slowHitCount:slowHitCount] as Map<String, Object>
            ahb.serverIpAddress = ecfi.localhostAddress?.getHostAddress() ?: "127.0.0.1"
            ahb.serverHostName = ecfi.localhostAddress?.getHostName() ?: "localhost"
            return ahb
        }
    }

    @CompileStatic
    void countArtifactHit(String artifactType, String artifactSubType, String artifactName, Map<String, Object> parameters,
                          long startTime, double runningTimeMillis, Long outputSize) {
        boolean isEntity = artifactType == 'entity' || artifactSubType == 'entity-implicit'
        // don't count the ones this calls
        if (isEntity && entitiesToSkipHitCount.contains(artifactName)) return
        ExecutionContextImpl eci = this.getEci()
        if (eci.getSkipStats() && artifactTypesForStatsSkip.contains(artifactType)) return

        boolean isSlowHit = false
        if (artifactPersistBin(artifactType, artifactSubType)) {
            String binKey = new StringBuilder(200).append(artifactType).append('.').append(artifactSubType).append(':').append(artifactName).toString()
            ArtifactStatsInfo statsInfo = artifactStatsInfoByType.get(binKey)
            if (statsInfo == null) {
                // consider seeding this from the DB using ArtifactHitReport to get all past data, or maybe not to better handle different servers/etc over time, etc
                statsInfo = new ArtifactStatsInfo()
                artifactStatsInfoByType.put(binKey, statsInfo)
            }

            ArtifactBinInfo abi = statsInfo.curHitBin
            if (abi == null) {
                abi = new ArtifactBinInfo(artifactType, artifactSubType, artifactName, startTime)
                statsInfo.curHitBin = abi
            }

            // has the current bin expired since the last hit record?
            long binStartTime = abi.startTime
            if (startTime > (binStartTime + hitBinLengthMillis)) {
                if (logger.isTraceEnabled()) logger.trace("Advancing ArtifactHitBin [${artifactType}.${artifactSubType}:${artifactName}] current hit start [${new Timestamp(startTime)}], bin start [${new Timestamp(abi.startTime)}] bin length ${hitBinLengthMillis/1000} seconds")
                advanceArtifactHitBin(statsInfo, artifactType, artifactSubType, artifactName, startTime, hitBinLengthMillis)
                abi = statsInfo.curHitBin
            }

            // handle current hit bin
            abi.incrementHitCount()
            // do something funny with these so we get a better avg and std dev, leave out the first result (count 2nd
            //     twice) if first hit is more than 2x the second because the first hit is almost always MUCH slower
            if (abi.hitCount == 2L && abi.totalTimeMillis > (runningTimeMillis * 2)) {
                abi.setTotalTimeMillis(runningTimeMillis * 2)
                abi.setTotalSquaredTime(runningTimeMillis * runningTimeMillis * 2)
            } else {
                abi.addRunningTime(runningTimeMillis)
            }
            if (runningTimeMillis < abi.minTimeMillis) abi.setMinTimeMillis(runningTimeMillis)
            if (runningTimeMillis > abi.maxTimeMillis) abi.setMaxTimeMillis(runningTimeMillis)

            // handle stats since start
            statsInfo.incrementHitCount()
            long statsHitCount = statsInfo.hitCount
            if (statsHitCount == 2L && (statsInfo.totalTimeMillis) > (runningTimeMillis * 2) ) {
                statsInfo.setTotalTimeMillis(runningTimeMillis * 2)
                statsInfo.setTotalSquaredTime(runningTimeMillis * runningTimeMillis * 2)
            } else {
                statsInfo.addRunningTime(runningTimeMillis)
            }
            // check for slow hits
            if (statsHitCount > checkSlowThreshold) {
                // calc new average and standard deviation
                double average = statsInfo.getAverage()
                double stdDev = statsInfo.getStdDev()

                // if runningTime is more than 2.6 std devs from the avg, count it and possibly log it
                // using 2.6 standard deviations because 2 would give us around 5% of hits (normal distro), shooting for more like 1%
                double slowTime = average + (stdDev * 2.6)
                if (slowTime != 0 && runningTimeMillis > slowTime) {
                    if (runningTimeMillis > userImpactMinMillis)
                        logger.warn("Slow hit to ${binKey} running time ${runningTimeMillis} is greater than average [${average}] plus 2 standard deviations [${stdDev}]")
                    abi.incrementSlowHitCount()
                    statsInfo.incrementSlowHitCount()
                    isSlowHit = true
                }
            }
        }
        // NOTE: never save individual hits for entity artifact hits, way too heavy and also avoids self-reference
        //     (could also be done by checking for ArtifactHit/etc of course)
        // Always save slow hits above userImpactMinMillis regardless of settings
        if (!isEntity && ((isSlowHit && runningTimeMillis > userImpactMinMillis) || artifactPersistHit(artifactType, artifactSubType))) {
            Map<String, Object> ahp = [visitId:eci.user.visitId, userId:eci.user.userId, isSlowHit:(isSlowHit ? 'Y' : 'N'),
                                       artifactType:artifactType, artifactSubType:artifactSubType, artifactName:artifactName,
                                       startDateTime:new Timestamp(startTime), runningTimeMillis:runningTimeMillis] as Map<String, Object>

            if (parameters) {
                StringBuilder ps = new StringBuilder()
                for (Map.Entry<String, Object> pme in parameters.entrySet()) {
                    if (!pme.value) continue
                    if (pme.key?.contains("password")) continue
                    if (ps.length() > 0) ps.append(",")
                    ps.append(pme.key).append("=").append(pme.value)
                }
                if (ps.length() > 255) ps.delete(255, ps.length())
                ahp.parameterString = ps.toString()
            }
            if (outputSize != null) ahp.outputSize = outputSize
            if (eci.getMessage().hasError()) {
                ahp.wasError = "Y"
                StringBuilder errorMessage = new StringBuilder()
                for (String curErr in eci.message.errors) errorMessage.append(curErr).append(";")
                if (errorMessage.length() > 255) errorMessage.delete(255, errorMessage.length())
                ahp.errorMessage = errorMessage.toString()
            } else {
                ahp.wasError = "N"
            }
            if (eci.web != null) {
                String fullUrl = eci.web.getRequestUrl()
                fullUrl = (fullUrl.length() > 255) ? fullUrl.substring(0, 255) : fullUrl.toString()
                ahp.requestUrl = fullUrl
                ahp.referrerUrl = eci.web.request.getHeader("Referrer") ?: ""
            }

            ahp.serverIpAddress = localhostAddress?.getHostAddress() ?: "127.0.0.1"
            ahp.serverHostName = localhostAddress?.getHostName() ?: "localhost"

            // call async, let the server do it whenever
            eci.service.async().name("create", "moqui.server.ArtifactHit").parameters(ahp).call()
        }
    }

    @CompileStatic
    protected synchronized void advanceArtifactHitBin(ArtifactStatsInfo statsInfo, String artifactType, String artifactSubType,
                                                     String artifactName, long startTime, int hitBinLengthMillis) {
        ArtifactBinInfo abi = statsInfo.curHitBin
        if (abi == null) {
            statsInfo.curHitBin = new ArtifactBinInfo(artifactType, artifactSubType, artifactName, startTime)
            return
        }

        // check the time again and return just in case something got in while waiting with the same type
        long binStartTime = abi.startTime
        if (startTime < (binStartTime + hitBinLengthMillis)) return

        // otherwise, persist the old and create a new one
        Map<String, Object> ahb = abi.makeAhbMap(this, new Timestamp(binStartTime + hitBinLengthMillis))
        // do this sync to avoid overhead of job scheduling for a very simple service call, and to avoid infinite recursion when EntityJobStore is in place
        try {
            executionContext.service.sync().name("create", "moqui.server.ArtifactHitBin").parameters(ahb)
                    .requireNewTransaction(true).call()
            if (executionContext.message.hasError()) {
                logger.error("Error creating ArtifactHitBin: ${executionContext.message.getErrorsString()}")
                executionContext.message.clearErrors()
            }
        } catch (Throwable t) {
            executionContext.message.clearErrors()
            logger.error("Error creating ArtifactHitBin", t)
            // just return, don't advance the bin so we can try again to save it later
            return
        }

        statsInfo.curHitBin = new ArtifactBinInfo(artifactType, artifactSubType, artifactName, startTime)
    }

    // ========== Configuration File Merging Methods ==========

    protected void mergeConfigNodes(Node baseNode, Node overrideNode) {
        mergeSingleChild(baseNode, overrideNode, "tools")

        if (overrideNode."cache-list") {
            mergeNodeWithChildKey((Node) baseNode."cache-list"[0], (Node) overrideNode."cache-list"[0], "cache", "name")
        }
        
        if (overrideNode."server-stats") {
            // the artifact-stats nodes have 2 keys: type, sub-type; can't use the normal method
            Node ssNode = baseNode."server-stats"[0]
            Node overrideSsNode = overrideNode."server-stats"[0]
            // override attributes for this node
            ssNode.attributes().putAll(overrideSsNode.attributes())
            for (Node childOverrideNode in (Collection<Node>) overrideSsNode."artifact-stats") {
                String type = childOverrideNode.attribute("type")
                String subType = childOverrideNode.attribute("sub-type")
                Node childBaseNode = (Node) ssNode."artifact-stats"?.find({ it."@type" == type &&
                        (it."@sub-type" == subType || (!it."@sub-type" && !subType)) })
                if (childBaseNode) {
                    // merge the node attributes
                    childBaseNode.attributes().putAll(childOverrideNode.attributes())
                } else {
                    // no matching child base node, so add a new one
                    ssNode.append(childOverrideNode)
                }
            }
        }

        if (overrideNode."webapp-list") {
            mergeNodeWithChildKey((Node) baseNode."webapp-list"[0], (Node) overrideNode."webapp-list"[0], "webapp", "name")
        }

        if (overrideNode."artifact-execution-facade") {
            mergeNodeWithChildKey((Node) baseNode."artifact-execution-facade"[0],
                    (Node) overrideNode."artifact-execution-facade"[0], "artifact-execution", "type")
        }

        if (overrideNode."user-facade") {
            Node ufBaseNode = baseNode."user-facade"[0]
            Node ufOverrideNode = overrideNode."user-facade"[0]
            mergeSingleChild(ufBaseNode, ufOverrideNode, "password")
            mergeSingleChild(ufBaseNode, ufOverrideNode, "login")
        }

        if (overrideNode."transaction-facade") {
            Node tfBaseNode = baseNode."transaction-facade"[0]
            Node tfOverrideNode = overrideNode."transaction-facade"[0]
            tfBaseNode.attributes().putAll(tfOverrideNode.attributes())
            mergeSingleChild(tfBaseNode, tfOverrideNode, "server-jndi")
            mergeSingleChild(tfBaseNode, tfOverrideNode, "transaction-jndi")
            mergeSingleChild(tfBaseNode, tfOverrideNode, "transaction-internal")
        }

        if (overrideNode."resource-facade") {
            mergeNodeWithChildKey((Node) baseNode."resource-facade"[0], (Node) overrideNode."resource-facade"[0],
                    "resource-reference", "scheme")
            mergeNodeWithChildKey((Node) baseNode."resource-facade"[0], (Node) overrideNode."resource-facade"[0],
                    "template-renderer", "extension")
            mergeNodeWithChildKey((Node) baseNode."resource-facade"[0], (Node) overrideNode."resource-facade"[0],
                    "script-runner", "extension")
        }

        if (overrideNode."screen-facade") {
            mergeNodeWithChildKey((Node) baseNode."screen-facade"[0], (Node) overrideNode."screen-facade"[0],
                    "screen-text-output", "type")
        }

        if (overrideNode."service-facade") {
            Node sfBaseNode = baseNode."service-facade"[0]
            Node sfOverrideNode = overrideNode."service-facade"[0]
            mergeNodeWithChildKey(sfBaseNode, sfOverrideNode, "service-location", "name")
            mergeNodeWithChildKey(sfBaseNode, sfOverrideNode, "service-type", "name")
            mergeNodeWithChildKey(sfBaseNode, sfOverrideNode, "startup-service", "name")

            // handle thread-pool
            Node tpOverrideNode = sfOverrideNode."thread-pool"[0]
            if (tpOverrideNode) {
                Node tpBaseNode = sfBaseNode."thread-pool"[0]
                if (tpBaseNode) {
                    mergeNodeWithChildKey(tpBaseNode, tpOverrideNode, "run-from-pool", "name")
                } else {
                    sfBaseNode.append(tpOverrideNode)
                }
            }

            // handle jms-service, just copy all over
            for (Node jsOverrideNode in sfOverrideNode."jms-service") {
                sfBaseNode.append(jsOverrideNode)
            }
        }

        if (overrideNode."entity-facade") {
            Node efBaseNode = baseNode."entity-facade"[0]
            Node efOverrideNode = overrideNode."entity-facade"[0]
            mergeNodeWithChildKey(efBaseNode, efOverrideNode, "datasource", "group-name")
            mergeSingleChild(efBaseNode, efOverrideNode, "server-jndi")
            // for load-entity and load-data just copy over override nodes
            for (Node copyNode in efOverrideNode."load-entity") efBaseNode.append(copyNode)
            for (Node copyNode in efOverrideNode."load-data") efBaseNode.append(copyNode)
        }

        if (overrideNode."database-list") {
            mergeNodeWithChildKey((Node) baseNode."database-list"[0], (Node) overrideNode."database-list"[0], "dictionary-type", "type")
            mergeNodeWithChildKey((Node) baseNode."database-list"[0], (Node) overrideNode."database-list"[0], "database", "name")
        }

        if (overrideNode."repository-list") {
            mergeNodeWithChildKey((Node) baseNode."repository-list"[0], (Node) overrideNode."repository-list"[0], "repository", "name")
        }

        if (overrideNode."component-list") {
            if (!baseNode."component-list") baseNode.appendNode("component-list")
            Node baseComponentNode = baseNode."component-list"[0]
            for (Node copyNode in overrideNode."component-list"[0].children()) baseComponentNode.append(copyNode)
            // mergeNodeWithChildKey((Node) baseNode."component-list"[0], (Node) overrideNode."component-list"[0], "component-dir", "location")
            // mergeNodeWithChildKey((Node) baseNode."component-list"[0], (Node) overrideNode."component-list"[0], "component", "name")
        }
    }

    protected static void mergeSingleChild(Node baseNode, Node overrideNode, String childNodeName) {
        Node childOverrideNode = (Node) overrideNode[childNodeName][0]
        if (childOverrideNode) {
            Node childBaseNode = (Node) baseNode[childNodeName][0]
            if (childBaseNode) {
                childBaseNode.attributes().putAll(childOverrideNode.attributes())
            } else {
                baseNode.append(childOverrideNode)
            }
        }
    }

    protected void mergeNodeWithChildKey(Node baseNode, Node overrideNode, String childNodesName, String keyAttributeName) {
        // override attributes for this node
        baseNode.attributes().putAll(overrideNode.attributes())

        for (Node childOverrideNode in (Collection<Node>) overrideNode[childNodesName]) {
            String keyValue = childOverrideNode.attribute(keyAttributeName)
            Node childBaseNode = (Node) baseNode[childNodesName]?.find({ it.attribute(keyAttributeName) == keyValue })

            if (childBaseNode) {
                // merge the node attributes
                childBaseNode.attributes().putAll(childOverrideNode.attributes())

                // merge child nodes for specific nodes
                if ("webapp" == childNodesName) {
                    mergeWebappChildNodes(childBaseNode, childOverrideNode)
                } else if ("database" == childNodesName) {
                    // handle database -> database-type@type
                    mergeNodeWithChildKey(childBaseNode, childOverrideNode, "database-type", "type")
                } else if ("datasource" == childNodesName) {
                    // handle the jndi-jdbc and inline-jdbc nodes: if either exist in override have it totally remove both from base, then copy over
                    if (childOverrideNode."jndi-jdbc" || childOverrideNode."inline-jdbc") {
                        if (childBaseNode."jndi-jdbc") childBaseNode.remove((Node) childBaseNode."jndi-jdbc"[0])
                        if (childBaseNode."inline-jdbc") childBaseNode.remove((Node) childBaseNode."inline-jdbc"[0])

                        if (childOverrideNode."inline-jdbc") {
                            childBaseNode.append((Node) childOverrideNode."inline-jdbc"[0])
                        } else if (childOverrideNode."jndi-jdbc") {
                            childBaseNode.append((Node) childOverrideNode."jndi-jdbc"[0])
                        }
                    }
                }
            } else {
                // no matching child base node, so add a new one
                baseNode.append(childOverrideNode)
            }
        }
    }

    protected void mergeWebappChildNodes(Node baseNode, Node overrideNode) {
        mergeNodeWithChildKey(baseNode, overrideNode, "root-screen", "host")
        // handle webapp -> first-hit-in-visit[1], after-request[1], before-request[1], after-login[1], before-logout[1], root-screen[1]
        mergeWebappActions(baseNode, overrideNode, "first-hit-in-visit")
        mergeWebappActions(baseNode, overrideNode, "after-request")
        mergeWebappActions(baseNode, overrideNode, "before-request")
        mergeWebappActions(baseNode, overrideNode, "after-login")
        mergeWebappActions(baseNode, overrideNode, "before-logout")
        mergeWebappActions(baseNode, overrideNode, "after-startup")
        mergeWebappActions(baseNode, overrideNode, "before-shutdown")
    }

    protected static void mergeWebappActions(Node baseWebappNode, Node overrideWebappNode, String childNodeName) {
        List<Node> overrideActionNodes = overrideWebappNode.get(childNodeName)?.getAt(0)?."actions"?.getAt(0)?.children()
        if (overrideActionNodes) {
            Node childNode = (Node) baseWebappNode[childNodeName][0]
            if (!childNode) {
                childNode = baseWebappNode.appendNode(childNodeName)
            }
            Node actionsNode = childNode.actions[0]
            if (!actionsNode) {
                actionsNode = childNode.appendNode("actions")
            }

            for (Node overrideActionNode in overrideActionNodes) {
                actionsNode.append(overrideActionNode)
            }
        }
    }

    Node getWebappNode(String webappName) { return (Node) confXmlRoot."webapp-list"[0]."webapp".find({ it."@name" == webappName }) }

    WebappInfo getWebappInfo(String webappName) {
        if (webappInfoMap.containsKey(webappName)) return webappInfoMap.get(webappName)
        return makeWebappInfo(webappName)
    }
    protected synchronized WebappInfo makeWebappInfo(String webappName) {
        WebappInfo wi = new WebappInfo(webappName, this)
        webappInfoMap.put(webappName, wi)
        return wi
    }

    static class WebappInfo {
        String webappName
        XmlAction firstHitInVisitActions = null
        XmlAction beforeRequestActions = null
        XmlAction afterRequestActions = null
        XmlAction afterLoginActions = null
        XmlAction beforeLogoutActions = null
        XmlAction afterStartupActions = null
        XmlAction beforeShutdownActions = null

        WebappInfo(String webappName, ExecutionContextFactoryImpl ecfi) {
            this.webappName = webappName
            // prep actions
            Node webappNode = ecfi.getWebappNode(webappName)
            if (webappNode."first-hit-in-visit")
                this.firstHitInVisitActions = new XmlAction(ecfi, (Node) webappNode."first-hit-in-visit"[0]."actions"[0],
                        "webapp_${webappName}.first_hit_in_visit.actions")

            if (webappNode."before-request")
                this.beforeRequestActions = new XmlAction(ecfi, (Node) webappNode."before-request"[0]."actions"[0],
                        "webapp_${webappName}.before_request.actions")
            if (webappNode."after-request")
                this.afterRequestActions = new XmlAction(ecfi, (Node) webappNode."after-request"[0]."actions"[0],
                        "webapp_${webappName}.after_request.actions")

            if (webappNode."after-login")
                this.afterLoginActions = new XmlAction(ecfi, (Node) webappNode."after-login"[0]."actions"[0],
                        "webapp_${webappName}.after_login.actions")
            if (webappNode."before-logout")
                this.beforeLogoutActions = new XmlAction(ecfi, (Node) webappNode."before-logout"[0]."actions"[0],
                        "webapp_${webappName}.before_logout.actions")

            if (webappNode."after-startup")
                this.afterStartupActions = new XmlAction(ecfi, (Node) webappNode."after-startup"[0]."actions"[0],
                        "webapp_${webappName}.after_startup.actions")
            if (webappNode."before-shutdown")
                this.beforeShutdownActions = new XmlAction(ecfi, (Node) webappNode."before-shutdown"[0]."actions"[0],
                        "webapp_${webappName}.before_shutdown.actions")
        }
    }

    @Override
    String toString() { return "ExecutionContextFactory" }
}
