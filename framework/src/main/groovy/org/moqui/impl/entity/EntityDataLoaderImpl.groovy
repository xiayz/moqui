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
package org.moqui.impl.entity

import groovy.json.JsonSlurper
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import org.moqui.BaseException
import org.moqui.context.ExecutionContext
import org.moqui.impl.service.ServiceCallSyncImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.service.ServiceCallSync

import javax.sql.rowset.serial.SerialBlob
import javax.xml.parsers.SAXParserFactory

import org.apache.commons.codec.binary.Base64

import org.moqui.context.TransactionException
import org.moqui.context.TransactionFacade
import org.moqui.context.ResourceReference
import org.moqui.entity.EntityException
import org.moqui.entity.EntityDataLoader
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue

import org.slf4j.LoggerFactory
import org.slf4j.Logger

import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.Attributes
import org.xml.sax.XMLReader
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException

class EntityDataLoaderImpl implements EntityDataLoader {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDataLoaderImpl.class)

    protected EntityFacadeImpl efi
    protected ServiceFacadeImpl sfi

    // NOTE: these are Groovy Beans style with no access modifier, results in private fields with implicit getters/setters

    List<String> locationList = new LinkedList<String>()
    String xmlText = null
    String csvText = null
    String jsonText = null
    Set<String> dataTypes = new HashSet<String>()
    List<String> componentNameList = new LinkedList<String>()

    int transactionTimeout = 600
    boolean useTryInsert = false
    boolean dummyFks = false
    boolean disableEeca = false

    char csvDelimiter = ','
    char csvCommentStart = '#'
    char csvQuoteChar = '"'

    EntityDataLoaderImpl(EntityFacadeImpl efi) {
        this.efi = efi
        this.sfi = efi.getEcfi().getServiceFacade()
    }

    EntityFacadeImpl getEfi() { return efi }

    @Override
    EntityDataLoader location(String location) { this.locationList.add(location); return this }
    @Override
    EntityDataLoader locationList(List<String> ll) { this.locationList.addAll(ll); return this }
    @Override
    EntityDataLoader xmlText(String xmlText) { this.xmlText = xmlText; return this }
    @Override
    EntityDataLoader csvText(String csvText) { this.csvText = csvText; return this }
    @Override
    EntityDataLoader jsonText(String jsonText) { this.jsonText = jsonText; return this }
    @Override
    EntityDataLoader dataTypes(Set<String> dataTypes) {
        for (String dt in dataTypes) this.dataTypes.add(dt.trim())
        return this
    }
    @Override
    EntityDataLoader componentNameList(List<String> componentNames) {
        for (String cn in componentNames) this.componentNameList.add(cn.trim())
        return this
    }

    @Override
    EntityDataLoader transactionTimeout(int tt) { this.transactionTimeout = tt; return this }
    @Override
    EntityDataLoader useTryInsert(boolean useTryInsert) { this.useTryInsert = useTryInsert; return this }
    @Override
    EntityDataLoader dummyFks(boolean dummyFks) { this.dummyFks = dummyFks; return this }
    @Override
    EntityDataLoader disableEntityEca(boolean disableEeca) { this.disableEeca = disableEeca; return this }

    @Override
    EntityDataLoader csvDelimiter(char delimiter) { this.csvDelimiter = delimiter; return this }
    @Override
    EntityDataLoader csvCommentStart(char commentStart) { this.csvCommentStart = commentStart; return this }
    @Override
    EntityDataLoader csvQuoteChar(char quoteChar) { this.csvQuoteChar = quoteChar; return this }

    List<String> check() {
        CheckValueHandler cvh = new CheckValueHandler(this)
        EntityXmlHandler exh = new EntityXmlHandler(this, cvh)
        EntityCsvHandler ech = new EntityCsvHandler(this, cvh)
        EntityJsonHandler ejh = new EntityJsonHandler(this, cvh)

        internalRun(exh, ech, ejh)
        return cvh.getMessageList()
    }

    long load() {
        LoadValueHandler lvh = new LoadValueHandler(this)
        EntityXmlHandler exh = new EntityXmlHandler(this, lvh)
        EntityCsvHandler ech = new EntityCsvHandler(this, lvh)
        EntityJsonHandler ejh = new EntityJsonHandler(this, lvh)

        internalRun(exh, ech, ejh)
        return exh.getValuesRead() + ech.getValuesRead() + ejh.getValuesRead()
    }

    EntityList list() {
        ListValueHandler lvh = new ListValueHandler(this)
        EntityXmlHandler exh = new EntityXmlHandler(this, lvh)
        EntityCsvHandler ech = new EntityCsvHandler(this, lvh)
        EntityJsonHandler ejh = new EntityJsonHandler(this, lvh)

        internalRun(exh, ech, ejh)
        return lvh.entityList
    }

    void internalRun(EntityXmlHandler exh, EntityCsvHandler ech, EntityJsonHandler ejh) {
        // make sure reverse relationships exist
        efi.createAllAutoReverseManyRelationships()

        boolean reenableEeca = false
        if (this.disableEeca) reenableEeca = !this.efi.ecfi.eci.artifactExecution.disableEntityEca()

        // if no xmlText or locations, so find all of the component and entity-facade files
        if (!this.xmlText && !this.csvText && !this.jsonText && !this.locationList) {
            // if we're loading seed type data, add configured (Moqui Conf XML) entity def files to the list of locations to load
            if (!componentNameList && (!dataTypes || dataTypes.contains("seed"))) {
                for (ResourceReference entityRr in efi.getConfEntityFileLocations())
                    if (!entityRr.location.endsWith(".eecas.xml")) locationList.add(entityRr.location)
            }

            // loop through all of the entity-facade.load-data nodes
            if (!componentNameList) {
                for (Node loadData in efi.ecfi.getConfXmlRoot()."entity-facade"[0]."load-data") {
                    locationList.add((String) loadData."@location")
                }
            }

            // if we're loading seed type data, add COMPONENT entity def files to the list of locations to load
            if (!dataTypes || dataTypes.contains("seed")) {
                for (ResourceReference entityRr in efi.getComponentEntityFileLocations(componentNameList))
                    if (!entityRr.location.endsWith(".eecas.xml")) locationList.add(entityRr.location)
            }

            List<String> componentBaseLocations
            if (componentNameList) {
                componentBaseLocations = []
                for (String cn in componentNameList)
                    componentBaseLocations.add(efi.ecfi.getComponentBaseLocations().get(cn))
            } else {
                componentBaseLocations = new ArrayList(efi.ecfi.getComponentBaseLocations().values())
            }
            for (String location in componentBaseLocations) {
                ResourceReference dataDirRr = efi.ecfi.resourceFacade.getLocationReference(location + "/data")
                if (dataDirRr.supportsAll()) {
                    // if directory doesn't exist skip it, component doesn't have a data directory
                    if (!dataDirRr.exists || !dataDirRr.isDirectory()) continue
                    // get all files in the directory
                    TreeMap<String, ResourceReference> dataDirEntries = new TreeMap<String, ResourceReference>()
                    for (ResourceReference dataRr in dataDirRr.directoryEntries) {
                        if (!dataRr.isFile() || (!dataRr.location.endsWith(".xml") && !dataRr.location.endsWith(".csv")
                                && !dataRr.location.endsWith(".json"))) continue
                        dataDirEntries.put(dataRr.getFileName(), dataRr)
                    }
                    for (Map.Entry<String, ResourceReference> dataDirEntry in dataDirEntries) {
                        locationList.add(dataDirEntry.getValue().location)
                    }
                } else {
                    // just warn here, no exception because any non-file component location would blow everything up
                    logger.warn("Cannot load entity data file in component location [${location}] because protocol [${dataDirRr.uri.scheme}] is not yet supported.")
                }
            }
        }
        if (locationList && logger.isInfoEnabled()) {
            StringBuilder lm = new StringBuilder("Loading entity data from the following locations: ")
            for (String loc in locationList) lm.append("\n - ").append(loc)
            logger.info(lm.toString())
            logger.info("Loading data types: ${dataTypes ?: 'ALL'}")
        }

        // efi.createAllAutoReverseManyRelationships()
        // logger.warn("========== Waiting 45s to attach profiler")
        // Thread.sleep(45000)

        TransactionFacade tf = efi.ecfi.transactionFacade
        boolean suspendedTransaction = false
        try {
            if (tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            // load the XML text in its own transaction
            if (this.xmlText) {
                boolean beganTransaction = tf.begin(transactionTimeout)
                try {
                    XMLReader reader = SAXParserFactory.newInstance().newSAXParser().XMLReader
                    exh.setLocation("xmlText")
                    reader.setContentHandler(exh)
                    reader.parse(new InputSource(new StringReader(this.xmlText)))
                } catch (Throwable t) {
                    tf.rollback(beganTransaction, "Error loading XML entity data", t)
                    throw t
                } finally {
                    if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
                }
            }

            // load the CSV text in its own transaction
            if (this.csvText) {
                boolean beganTransaction = tf.begin(transactionTimeout)
                InputStream csvInputStream = new ByteArrayInputStream(csvText.getBytes("UTF-8"))
                try {
                    ech.loadFile("csvText", csvInputStream)
                } catch (Throwable t) {
                    tf.rollback(beganTransaction, "Error loading CSV entity data", t)
                    throw t
                } finally {
                    if (csvInputStream != null) csvInputStream.close()
                    if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
                }
            }

            // load the CSV text in its own transaction
            if (this.jsonText) {
                boolean beganTransaction = tf.begin(transactionTimeout)
                InputStream jsonInputStream = new ByteArrayInputStream(jsonText.getBytes("UTF-8"))
                try {
                    ejh.loadFile("csvText", jsonInputStream)
                } catch (Throwable t) {
                    tf.rollback(beganTransaction, "Error loading JSON entity data", t)
                    throw t
                } finally {
                    if (jsonInputStream != null) jsonInputStream.close()
                    if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
                }
            }

            // load each file in its own transaction
            for (String location in this.locationList) {
                loadSingleFile(location, exh, ech, ejh)
            }
        } catch (TransactionException e) {
            throw e
        } finally {
            if (suspendedTransaction) tf.resume()
        }

        if (reenableEeca) this.efi.ecfi.eci.artifactExecution.enableEntityEca()

        // logger.warn("========== Done loading, waiting for a long time so process is still running for profiler")
        // Thread.sleep(60*1000*100)
    }

    void loadSingleFile(String location, EntityXmlHandler exh, EntityCsvHandler ech, EntityJsonHandler ejh) {
        TransactionFacade tf = efi.ecfi.transactionFacade
        boolean beganTransaction = tf.begin(transactionTimeout)
        try {
            InputStream inputStream = null
            try {
                logger.info("Loading entity data from [${location}]")
                long beforeTime = System.currentTimeMillis()

                inputStream = efi.ecfi.resourceFacade.getLocationStream(location)

                if (location.endsWith(".xml")) {
                    long beforeRecords = exh.valuesRead ?: 0
                    XMLReader reader = SAXParserFactory.newInstance().newSAXParser().XMLReader
                    exh.setLocation(location)
                    reader.setContentHandler(exh)
                    reader.parse(new InputSource(inputStream))
                    logger.info("Loaded ${(exh.valuesRead?:0) - beforeRecords} records from [${location}] in ${((System.currentTimeMillis() - beforeTime)/1000)} seconds")
                } else if (location.endsWith(".csv")) {
                    long beforeRecords = ech.valuesRead ?: 0
                    if (ech.loadFile(location, inputStream)) {
                        logger.info("Loaded ${(ech.valuesRead?:0) - beforeRecords} records from [${location}] in ${((System.currentTimeMillis() - beforeTime)/1000)} seconds")
                    }
                } else if (location.endsWith(".json")) {
                    long beforeRecords = ejh.valuesRead ?: 0
                    if (ejh.loadFile(location, inputStream)) {
                        logger.info("Loaded ${(ejh.valuesRead?:0) - beforeRecords} records from [${location}] in ${((System.currentTimeMillis() - beforeTime)/1000)} seconds")
                    }
                }
            } catch (TypeToSkipException e) {
                // nothing to do, this just stops the parsing when we know the file is not in the types we want
            } finally {
                if (inputStream != null) inputStream.close()
            }
        } catch (Throwable t) {
            tf.rollback(beganTransaction, "Error loading entity data", t)
            throw new IllegalArgumentException("Error loading entity data file [${location}]", t)
        } finally {
            if (beganTransaction && tf.isTransactionInPlace()) tf.commit()

            ExecutionContext ec = efi.getEcfi().getExecutionContext()
            if (ec.message.hasError()) {
                logger.error("Error messages loading entity data: " + ec.message.getErrorsString())
                ec.message.clearErrors()
            }
        }
    }

    static abstract class ValueHandler {
        protected EntityDataLoaderImpl edli
        ValueHandler(EntityDataLoaderImpl edli) { this.edli = edli }
        abstract void handleValue(EntityValue value)
        abstract void handlePlainMap(String entityName, Map value)
        abstract void handleService(ServiceCallSync scs)
    }
    static class CheckValueHandler extends ValueHandler {
        protected List<String> messageList = new LinkedList()
        CheckValueHandler(EntityDataLoaderImpl edli) { super(edli) }
        List<String> getMessageList() { return messageList }
        void handleValue(EntityValue value) { value.checkAgainstDatabase(messageList) }
        void handlePlainMap(String entityName, Map value) {
            EntityList el = edli.getEfi().getValueListFromPlainMap(value, entityName)
            // logger.warn("=========== Check value: ${value}\nel: ${el}")
            for (EntityValue ev in el) ev.checkAgainstDatabase(messageList)
        }
        void handleService(ServiceCallSync scs) { messageList.add("Doing check only so not calling service [${scs.getServiceName()}] with parameters ${scs.getCurrentParameters()}") }
    }
    static class LoadValueHandler extends ValueHandler {
        protected ServiceFacadeImpl sfi
        protected ExecutionContext ec
        LoadValueHandler(EntityDataLoaderImpl edli) {
            super(edli)
            sfi = edli.getEfi().getEcfi().getServiceFacade()
            ec = edli.getEfi().getEcfi().getExecutionContext()
        }
        void handleValue(EntityValue value) {
            if (edli.dummyFks) value.checkFks(true)
            if (edli.useTryInsert) {
                try {
                    value.create()
                } catch (EntityException e) {
                    if (logger.isTraceEnabled()) logger.trace("Insert failed, trying update (${e.toString()})")
                    // retry, then if this fails we have a real error so let the exception fall through
                    value.update()
                }
            } else {
                value.createOrUpdate()
            }
        }
        void handlePlainMap(String entityName, Map value) {
            Map results = sfi.sync().name('store', entityName).parameters(value).call()
            if (logger.isTraceEnabled()) logger.trace("Called store service for entity [${entityName}] in data load, results: ${results}")
            if (ec.getMessage().hasError()) {
                String errStr = ec.getMessage().getErrorsString()
                ec.getMessage().clearErrors()
                throw new BaseException("Error handling data load plain Map: ${errStr}")
            }
        }
        void handleService(ServiceCallSync scs) {
            Map results = scs.call()
            if (logger.isInfoEnabled()) logger.info("Called service [${scs.getServiceName()}] in data load, results: ${results}")
            if (ec.getMessage().hasError()) {
                String errStr = ec.getMessage().getErrorsString()
                ec.getMessage().clearErrors()
                throw new BaseException("Error handling data load service call: ${errStr}")
            }
        }
    }
    static class ListValueHandler extends ValueHandler {
        protected EntityList el
        ListValueHandler(EntityDataLoaderImpl edli) { super(edli); el = new EntityListImpl(edli.efi) }
        EntityList getEntityList() { return el }
        void handleValue(EntityValue value) {
            el.add(value)
        }
        void handlePlainMap(String entityName, Map value) {
            EntityDefinition ed = edli.getEfi().getEntityDefinition(entityName)
            edli.getEfi().addValuesFromPlainMapRecursive(ed, value, el)
        }
        void handleService(ServiceCallSync scs) { logger.warn("For load to EntityList not calling service [${scs.getServiceName()}] with parameters ${scs.getCurrentParameters()}") }
    }

    static class TypeToSkipException extends RuntimeException {
        TypeToSkipException() { }
    }

    static class EntityXmlHandler extends DefaultHandler {
        protected Locator locator
        protected EntityDataLoaderImpl edli
        protected ValueHandler valueHandler

        protected EntityDefinition currentEntityDef = null
        protected ServiceDefinition currentServiceDef = null
        protected Map rootValueMap = null
        // use a List as a stack, element 0 is the top
        protected List<Map> valueMapStack = null
        protected List<EntityDefinition> relatedEdStack = null

        protected String currentFieldName = null
        protected StringBuilder currentFieldValue = null
        protected long valuesRead = 0
        protected List<String> messageList = new LinkedList()
        String location

        protected boolean loadElements = false

        EntityXmlHandler(EntityDataLoaderImpl edli, ValueHandler valueHandler) {
            this.edli = edli
            this.valueHandler = valueHandler
        }

        ValueHandler getValueHandler() { return valueHandler }
        long getValuesRead() { return valuesRead }
        List<String> getMessageList() { return messageList }

        void startElement(String ns, String localName, String qName, Attributes attributes) {
            // logger.info("startElement ns [${ns}], localName [${localName}] qName [${qName}]")
            String type = null
            if (qName == "entity-facade-xml") { type = attributes.getValue("type") }
            else if (qName == "seed-data") { type = "seed" }
            if (type && edli.dataTypes && !edli.dataTypes.contains(type)) {
                if (logger.isInfoEnabled()) logger.info("Skipping file [${location}], is a type to skip (${type})")
                throw new TypeToSkipException()
            }

            if (qName == "entity-facade-xml") {
                loadElements = true
                return
            } else if (qName == "seed-data") {
                loadElements = true
                return
            }
            if (!loadElements) return

            String entityName = qName
            // get everything after a colon, but replace - with # for verb#noun separation
            if (entityName.contains(':')) entityName = entityName.substring(entityName.indexOf(':') + 1)
            if (entityName.contains('-')) entityName = entityName.replace('-', '#')

            if (currentEntityDef != null) {
                EntityDefinition checkEd = currentEntityDef
                if (relatedEdStack) checkEd = relatedEdStack.get(0)
                if (checkEd.isField(entityName)) {
                    // nested value/CDATA element
                    currentFieldName = entityName
                } else if (checkEd.getRelationshipInfo(entityName) != null) {
                    EntityDefinition.RelationshipInfo relInfo = checkEd.getRelationshipInfo(entityName)
                    Map curRelMap = getAttributesMap(attributes, relInfo.relatedEd)
                    String relationshipName = relInfo.getRelationshipName()
                    if (valueMapStack) {
                        Map prevValueMap = valueMapStack.get(0)
                        if (prevValueMap.containsKey(relationshipName)) {
                            Object prevRelValue = prevValueMap.get(relationshipName)
                            if (prevRelValue instanceof List) {
                                prevRelValue.add(curRelMap)
                            } else {
                                prevValueMap.put(relationshipName, [prevRelValue, curRelMap])
                            }
                        } else {
                            prevValueMap.put(relationshipName, curRelMap)
                        }
                        valueMapStack.add(0, curRelMap)
                        relatedEdStack.add(0, relInfo.relatedEd)
                    } else {
                        if (rootValueMap.containsKey(relationshipName)) {
                            Object prevRelValue = rootValueMap.get(relationshipName)
                            if (prevRelValue instanceof List) {
                                prevRelValue.add(curRelMap)
                            } else {
                                rootValueMap.put(relationshipName, [prevRelValue, curRelMap])
                            }
                        } else {
                            rootValueMap.put(relationshipName, curRelMap)
                        }
                        valueMapStack = [curRelMap]
                        relatedEdStack = [relInfo.relatedEd]
                    }
                } else if (edli.efi.isEntityDefined(entityName)) {
                    EntityDefinition subEd = edli.efi.getEntityDefinition(entityName)
                    Map curRelMap = getAttributesMap(attributes, subEd)
                    String relationshipName = subEd.getFullEntityName()
                    if (valueMapStack) {
                        Map prevValueMap = valueMapStack.get(0)
                        if (prevValueMap.containsKey(relationshipName)) {
                            Object prevRelValue = prevValueMap.get(relationshipName)
                            if (prevRelValue instanceof List) {
                                prevRelValue.add(curRelMap)
                            } else {
                                prevValueMap.put(relationshipName, [prevRelValue, curRelMap])
                            }
                        } else {
                            prevValueMap.put(relationshipName, curRelMap)
                        }
                        valueMapStack.add(0, curRelMap)
                        relatedEdStack.add(0, subEd)
                    } else {
                        if (rootValueMap.containsKey(relationshipName)) {
                            Object prevRelValue = rootValueMap.get(relationshipName)
                            if (prevRelValue instanceof List) {
                                prevRelValue.add(curRelMap)
                            } else {
                                rootValueMap.put(relationshipName, [prevRelValue, curRelMap])
                            }
                        } else {
                            rootValueMap.put(relationshipName, curRelMap)
                        }
                        valueMapStack = [curRelMap]
                        relatedEdStack = [subEd]
                    }
                } else {
                    logger.warn("Found element [${entityName}] under element for entity [${checkEd.getFullEntityName()}] and it is not a field or relationship so ignoring")
                }
            } else if (currentServiceDef != null) {
                currentFieldName = qName
                // TODO: support nested elements for services? ie look for attributes, somehow handle subelements, etc
            } else {
                if (edli.efi.isEntityDefined(entityName)) {
                    currentEntityDef = edli.efi.getEntityDefinition(entityName)
                    rootValueMap = getAttributesMap(attributes, currentEntityDef)
                } else if (edli.sfi.isServiceDefined(entityName)) {
                    currentServiceDef = edli.sfi.getServiceDefinition(entityName)
                    rootValueMap = getAttributesMap(attributes, null)
                } else {
                    throw new SAXException("Found element [${qName}] name, transformed to [${entityName}], that is not a valid entity name or service name")
                }
            }
        }
        static Map getAttributesMap(Attributes attributes, EntityDefinition checkEd) {
            Map attrMap = [:]
            int length = attributes.getLength()
            for (int i = 0; i < length; i++) {
                String name = attributes.getLocalName(i)
                String value = attributes.getValue(i)
                if (!name) name = attributes.getQName(i)

                if (checkEd == null || checkEd.isField(name)) {
                    // treat empty strings as nulls
                    if (value) {
                        attrMap.put(name, value)
                    } else {
                        attrMap.put(name, null)
                    }
                } else {
                    logger.warn("Ignoring invalid attribute name [${name}] for entity [${checkEd.getFullEntityName()}] with value [${value}] because it is not field of that entity")
                }
            }
            return attrMap
        }

        void characters(char[] chars, int offset, int length) {
            if (rootValueMap && currentFieldName) {
                if (currentFieldValue == null) currentFieldValue = new StringBuilder()
                currentFieldValue.append(chars, offset, length)
            }
        }
        void endElement(String ns, String localName, String qName) {
            if (qName == "entity-facade-xml" || qName == "seed-data") {
                loadElements = false
                return
            }
            if (!loadElements) return

            if (currentFieldName != null) {
                if (currentFieldValue) {
                    if (currentEntityDef != null) {
                        if (currentEntityDef.isField(currentFieldName)) {
                            EntityDefinition.FieldInfo fieldInfo = currentEntityDef.getFieldInfo(currentFieldName)
                            String type = fieldInfo.type
                            if (type == "binary-very-long") {
                                byte[] binData = Base64.decodeBase64(currentFieldValue.toString())
                                rootValueMap.put(currentFieldName, new SerialBlob(binData))
                            } else {
                                rootValueMap.put(currentFieldName, currentFieldValue.toString())
                            }
                        } else {
                            logger.warn("Ignoring invalid field name [${currentFieldName}] found for the entity ${currentEntityDef.getFullEntityName()} with value ${currentFieldValue}")
                        }
                    } else if (currentServiceDef != null) {
                        rootValueMap.put(currentFieldName, currentFieldValue)
                    }
                    currentFieldValue = null
                }
                currentFieldName = null
            } else if (valueMapStack) {
                // end of nested relationship element, just pop the last
                valueMapStack.remove(0)
                relatedEdStack.remove(0)
                valuesRead++
            } else {
                if (currentEntityDef != null) {
                    // before we write currentValue check to see if PK is there, if not and it is one field, generate it from a sequence using the entity name
                    /* Don't need to do this here any more, now calling the store service which will handle it
                    if (!currentEntityDef.containsPrimaryKey(rootValueMap)) {
                        if (currentEntityDef.getPkFieldNames().size() == 1) {
                            currentEntityValue.setSequencedIdPrimary()
                        } else {
                            throw new SAXException("Cannot process value with incomplete primary key for [${currentEntityValue.getEntityName()}] with more than 1 primary key field: " + currentEntityValue)
                        }
                    }
                    */

                    try {
                        // if (currentEntityDef.getFullEntityName().contains("DbForm")) logger.warn("========= DbForm rootValueMap: ${rootValueMap}")
                        valueHandler.handlePlainMap(currentEntityDef.getFullEntityName(), rootValueMap)
                        valuesRead++
                        currentEntityDef = null
                    } catch (EntityException e) {
                        throw new SAXException("Error storing entity [${currentEntityDef.getFullEntityName()}] value: " + e.toString(), e)
                    }
                } else if (currentServiceDef != null) {
                    try {
                        ServiceCallSync currentScs = edli.sfi.sync().name(currentServiceDef.getServiceName()).parameters(rootValueMap)
                        valueHandler.handleService(currentScs)
                        valuesRead++
                        currentServiceDef = null
                    } catch (Exception e) {
                        throw new SAXException("Error running service [${currentServiceDef.getServiceName()}]: " + e.toString(), e)
                    }
                }
            }
        }

        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }
    }

    static class EntityCsvHandler {
        protected EntityDataLoaderImpl edli
        protected ValueHandler valueHandler

        protected long valuesRead = 0
        protected List<String> messageList = new LinkedList()

        EntityCsvHandler(EntityDataLoaderImpl edli, ValueHandler valueHandler) {
            this.edli = edli
            this.valueHandler = valueHandler
        }

        ValueHandler getValueHandler() { return valueHandler }
        long getValuesRead() { return valuesRead }
        List<String> getMessageList() { return messageList }

        boolean loadFile(String location, InputStream is) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))

            CSVParser parser = CSVFormat.newFormat(edli.csvDelimiter)
                    .withCommentMarker(edli.csvCommentStart)
                    .withQuote(edli.csvQuoteChar)
                    .withSkipHeaderRecord(true) // TODO: remove this? does it even do anything?
                    .withIgnoreEmptyLines(true)
                    .withIgnoreSurroundingSpaces(true)
                    .parse(reader)

            Iterator<CSVRecord> iterator = parser.iterator()

            if (!iterator.hasNext()) throw new BaseException("Not loading file [${location}], no data found")

            CSVRecord firstLineRecord = iterator.next()
            String entityName = firstLineRecord.get(0)
            boolean isService
            if (edli.efi.isEntityDefined(entityName)) {
                isService = false
            } else if (edli.sfi.isServiceDefined(entityName)) {
                isService = true
            } else {
                throw new BaseException("CSV first line first field [${entityName}] is not a valid entity name or service name")
            }

            if (firstLineRecord.size() > 1) {
                // second field is data type
                String type = firstLineRecord.get(1)
                if (type && edli.dataTypes && !edli.dataTypes.contains(type)) {
                    if (logger.isInfoEnabled()) logger.info("Skipping file [${location}], is a type to skip (${type})")
                    return false
                }
            }

            if (!iterator.hasNext()) throw new BaseException("Not loading file [${location}], no second (header) line found")
            CSVRecord headerRecord = iterator.next()
            Map<String, Integer> headerMap = [:]
            for (int i = 0; i < headerRecord.size(); i++) headerMap.put(headerRecord.get(i), i)

            // logger.warn("======== CSV entity/service [${entityName}] headerMap: ${headerMap}")
            while (iterator.hasNext()) {
                CSVRecord record = iterator.next()
                // logger.warn("======== CSV record: ${record.toString()}")
                if (isService) {
                    ServiceCallSyncImpl currentScs = (ServiceCallSyncImpl) edli.sfi.sync().name(entityName)
                    for (Map.Entry<String, Integer> header in headerMap)
                        currentScs.parameter(header.key, record.get((int) header.value))
                    valueHandler.handleService(currentScs)
                    valuesRead++
                } else {
                    EntityValueImpl currentEntityValue = (EntityValueImpl) edli.efi.makeValue(entityName)
                    for (Map.Entry<String, Integer> header in headerMap)
                        currentEntityValue.setString(header.key, record.get((int) header.value))

                    if (!currentEntityValue.containsPrimaryKey()) {
                        if (currentEntityValue.getEntityDefinition().getPkFieldNames().size() == 1) {
                            currentEntityValue.setSequencedIdPrimary()
                        } else {
                            throw new BaseException("Cannot process value with incomplete primary key for [${currentEntityValue.getEntityName()}] with more than 1 primary key field: " + currentEntityValue)
                        }
                    }

                    // logger.warn("======== CSV entity: ${currentEntityValue.toString()}")
                    valueHandler.handleValue(currentEntityValue)
                    valuesRead++
                }
            }
            return true
        }
    }

    static class EntityJsonHandler {
        protected EntityDataLoaderImpl edli
        protected ValueHandler valueHandler

        protected long valuesRead = 0
        protected List<String> messageList = new LinkedList()

        EntityJsonHandler(EntityDataLoaderImpl edli, ValueHandler valueHandler) {
            this.edli = edli
            this.valueHandler = valueHandler
        }

        ValueHandler getValueHandler() { return valueHandler }
        long getValuesRead() { return valuesRead }
        List<String> getMessageList() { return messageList }

        boolean loadFile(String location, InputStream is) {
            JsonSlurper slurper = new JsonSlurper()
            Object jsonObj
            try {
                jsonObj = slurper.parse(new BufferedReader(new InputStreamReader(is, "UTF-8")))
            } catch (Throwable t) {
                String errMsg = "Error parsing HTTP request body JSON: ${t.toString()}"
                logger.error(errMsg, t)
                throw new BaseException(errMsg, t)
            }

            String type = null
            List valueList
            if (jsonObj instanceof Map) {
                type = jsonObj."_dataType"
                valueList = [jsonObj]
            } else if (jsonObj instanceof List) {
                valueList = jsonObj
                Object firstValue = valueList?.get(0)
                if (firstValue instanceof Map) {
                    if (firstValue."_dataType") {
                        type = firstValue."_dataType"
                        valueList.remove(0)
                    }
                }
            } else {
                throw new BaseException("Root JSON field was not a Map/object or List/array, type is ${jsonObj.getClass().getName()}")
            }

            if (type && edli.dataTypes && !edli.dataTypes.contains(type)) {
                if (logger.isInfoEnabled()) logger.info("Skipping file [${location}], is a type to skip (${type})")
                return false
            }

            for (Object valueObj in valueList) {
                if (!(valueObj instanceof Map)) {
                    logger.warn("Found non-Map object in JSON import, skipping: ${valueObj}")
                    continue
                }

                Map value = (Map) valueObj

                String entityName = value."_entity"
                boolean isService
                if (edli.efi.isEntityDefined(entityName)) {
                    isService = false
                } else if (edli.sfi.isServiceDefined(entityName)) {
                    isService = true
                } else {
                    throw new BaseException("JSON _entity value [${entityName}] is not a valid entity name or service name")
                }

                if (isService) {
                    ServiceCallSyncImpl currentScs = (ServiceCallSyncImpl) edli.sfi.sync().name(entityName).parameters(value)
                    valueHandler.handleService(currentScs)
                    valuesRead++
                } else {
                    valueHandler.handlePlainMap(entityName, value)
                    // TODO: make this more complete, like counting nested Maps?
                    valuesRead++
                }
            }

            return true
        }
    }
}
