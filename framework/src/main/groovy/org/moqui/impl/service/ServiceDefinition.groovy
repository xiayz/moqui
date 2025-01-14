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
package org.moqui.impl.service

import groovy.transform.CompileStatic
import org.apache.commons.validator.routines.CreditCardValidator
import org.apache.commons.validator.routines.EmailValidator
import org.apache.commons.validator.routines.UrlValidator
import org.moqui.impl.FtlNodeWrapper
import org.moqui.impl.StupidJavaUtilities
import org.moqui.impl.StupidUtilities
import org.moqui.impl.StupidWebUtilities
import org.moqui.impl.actions.XmlAction
import org.moqui.context.ContextStack
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.EntityDefinition
import org.owasp.esapi.ValidationErrorList
import org.owasp.esapi.errors.IntrusionException
import org.owasp.esapi.errors.ValidationException

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ServiceDefinition {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceDefinition.class)

    protected final static EmailValidator emailValidator = EmailValidator.getInstance()
    protected final static UrlValidator urlValidator = new UrlValidator(UrlValidator.ALLOW_ALL_SCHEMES)

    protected ServiceFacadeImpl sfi
    protected Node serviceNode
    protected Node inParametersNode
    protected Node outParametersNode
    protected String path = null
    protected String verb = null
    protected String noun = null
    protected XmlAction xmlAction = null

    protected String internalAuthenticate
    protected String internalServiceType
    protected boolean internalTxIgnore
    protected boolean internalTxForceNew
    protected boolean internalTxUseCache
    protected Integer internalTransactionTimeout

    ServiceDefinition(ServiceFacadeImpl sfi, String path, Node sn) {
        this.sfi = sfi
        this.serviceNode = sn
        this.path = path
        this.verb = serviceNode."@verb"
        this.noun = serviceNode."@noun"

        Node inParameters = new Node(null, "in-parameters")
        Node outParameters = new Node(null, "out-parameters")

        // handle implements elements
        if (serviceNode."implements") for (Node implementsNode in serviceNode."implements") {
            String implServiceName = implementsNode."@service"
            String implRequired = implementsNode."@required" // no default here, only used if has a value
            ServiceDefinition sd = sfi.getServiceDefinition(implServiceName)
            if (sd == null) throw new IllegalArgumentException("Service [${implServiceName}] not found, specified in service.implements in service [${getServiceName()}]")

            // these are the first params to be set, so just deep copy them over
            if (sd.serviceNode."in-parameters"?.getAt(0)?."parameter") {
                for (Node parameter in sd.serviceNode."in-parameters"[0]."parameter") {
                    Node newParameter = StupidUtilities.deepCopyNode(parameter)
                    if (implRequired) newParameter.attributes().put("required", implRequired)
                    inParameters.append(newParameter)
                }
            }
            if (sd.serviceNode."out-parameters"?.getAt(0)?."parameter") {
                for (Node parameter in sd.serviceNode."out-parameters"[0]."parameter") {
                    Node newParameter = StupidUtilities.deepCopyNode(parameter)
                    if (implRequired) newParameter.attributes().put("required", implRequired)
                    outParameters.append(newParameter)
                }
            }
        }

        // expand auto-parameters and merge parameter in in-parameters and out-parameters
        // if noun is a valid entity name set it on parameters with valid field names on it
        EntityDefinition ed = null
        if (sfi.getEcfi().getEntityFacade().isEntityDefined(this.noun)) ed = sfi.getEcfi().getEntityFacade().getEntityDefinition(this.noun)
        for (Node paramNode in serviceNode."in-parameters"?.getAt(0)?.children()) {
            if (paramNode.name() == "auto-parameters") {
                mergeAutoParameters(inParameters, paramNode)
            } else if (paramNode.name() == "parameter") {
                mergeParameter(inParameters, paramNode, ed)
            }
        }
        for (Node paramNode in serviceNode."out-parameters"?.getAt(0)?.children()) {
            if (paramNode.name() == "auto-parameters") {
                mergeAutoParameters(outParameters, paramNode)
            } else if (paramNode.name() == "parameter") {
                mergeParameter(outParameters, paramNode, ed)
            }
        }

        /*
        // expand auto-parameters in in-parameters and out-parameters
        if (serviceNode."in-parameters"?.getAt(0)?."auto-parameters")
            for (Node autoParameters in serviceNode."in-parameters"[0]."auto-parameters")
                mergeAutoParameters(inParameters, autoParameters)
        if (serviceNode."out-parameters"?.getAt(0)?."auto-parameters")
            for (Node autoParameters in serviceNode."out-parameters"[0]."auto-parameters")
                mergeAutoParameters(outParameters, autoParameters)

        // merge in the explicitly defined parameter elements
        if (serviceNode."in-parameters"?.getAt(0)?."parameter")
            for (Node parameterNode in serviceNode."in-parameters"[0]."parameter")
                mergeParameter(inParameters, parameterNode)
        if (serviceNode."out-parameters"?.getAt(0)?."parameter")
            for (Node parameterNode in serviceNode."out-parameters"[0]."parameter")
                mergeParameter(outParameters, parameterNode)
        */

        // replace the in-parameters and out-parameters Nodes for the service
        if (serviceNode."in-parameters") serviceNode.remove((Node) serviceNode."in-parameters"[0])
        serviceNode.append(inParameters)
        if (serviceNode."out-parameters") serviceNode.remove((Node) serviceNode."out-parameters"[0])
        serviceNode.append(outParameters)

        if (logger.traceEnabled) logger.trace("After merge for service [${getServiceName()}] node is:\n${FtlNodeWrapper.prettyPrintNode(serviceNode)}")

        // if this is an inline service, get that now
        if (serviceNode."actions") {
            xmlAction = new XmlAction(sfi.ecfi, (Node) serviceNode."actions"[0], getServiceName())
        }

        internalAuthenticate = serviceNode."@authenticate" ?: "true"
        internalServiceType = serviceNode."@type" ?: "inline"
        internalTxIgnore = (serviceNode."@transaction" == "ignore")
        internalTxForceNew = (serviceNode."@transaction" == "force-new" || serviceNode."@transaction" == "force-cache")
        internalTxUseCache = (serviceNode."@transaction" == "cache" || serviceNode."@transaction" == "force-cache")
        if (serviceNode."@transaction-timeout") {
            internalTransactionTimeout = serviceNode."@transaction-timeout" as Integer
        } else {
            internalTransactionTimeout = null
        }

        inParametersNode = (Node) serviceNode."in-parameters"[0]
        outParametersNode = (Node) serviceNode."out-parameters"[0]
    }

    void mergeAutoParameters(Node parametersNode, Node autoParameters) {
        String entityName = autoParameters."@entity-name" ?: this.noun
        if (!entityName) throw new IllegalArgumentException("Error in auto-parameters in service [${getServiceName()}], no auto-parameters.@entity-name and no service.@noun for a default")
        EntityDefinition ed = sfi.ecfi.entityFacade.getEntityDefinition(entityName)
        if (ed == null) throw new IllegalArgumentException("Error in auto-parameters in service [${getServiceName()}], the entity-name or noun [${entityName}] is not a valid entity name")

        Set<String> fieldsToExclude = new HashSet<String>()
        if (autoParameters."exclude") for (Node excludeNode in autoParameters."exclude") {
            fieldsToExclude.add((String) excludeNode."@field-name")
        }

        String includeStr = autoParameters."@include" ?: "all"
        String requiredStr = autoParameters."@required" ?: "false"
        String allowHtmlStr = autoParameters."@allow-html" ?: "none"
        for (String fieldName in ed.getFieldNames(includeStr == "all" || includeStr == "pk",
                includeStr == "all" || includeStr == "nonpk", includeStr == "all" || includeStr == "nonpk")) {
            if (fieldsToExclude.contains(fieldName)) continue

            String javaType = sfi.ecfi.entityFacade.getFieldJavaType(ed.getFieldInfo(fieldName).type, ed)
            mergeParameter(parametersNode, fieldName,
                    [type:javaType, required:requiredStr, "allow-html":allowHtmlStr,
                            "entity-name":ed.getFullEntityName(), "field-name":fieldName])
        }
    }

    static void mergeParameter(Node parametersNode, Node overrideParameterNode, EntityDefinition ed) {
        Node baseParameterNode = mergeParameter(parametersNode, (String) overrideParameterNode."@name",
                overrideParameterNode.attributes())
        // merge description, subtype, ParameterValidations
        for (Node childNode in (Collection<Node>) overrideParameterNode.children()) {
            if (childNode.name() == "description" || childNode.name() == "subtype") {
                if (baseParameterNode[(String) childNode.name()]) baseParameterNode.remove((Node) baseParameterNode[(String) childNode.name()].getAt(0))
            }
            // is a validation, just add it in, or the original has been removed so add the new one
            baseParameterNode.append(childNode)
        }
        if (baseParameterNode."@entity-name") {
            if (!baseParameterNode."@field-name") baseParameterNode.attributes().put("field-name", baseParameterNode."@name")
        } else if (ed != null && ed.isField(baseParameterNode."@name")) {
            baseParameterNode.attributes().put("entity-name", ed.getFullEntityName())
            baseParameterNode.attributes().put("field-name", baseParameterNode."@name")
        }
    }

    static Node mergeParameter(Node parametersNode, String parameterName, Map attributeMap) {
        Node baseParameterNode = (Node) parametersNode."parameter".find({ it."@name" == parameterName })
        if (baseParameterNode == null) baseParameterNode = parametersNode.appendNode("parameter", [name:parameterName])
        baseParameterNode.attributes().putAll(attributeMap)
        return baseParameterNode
    }

    @CompileStatic
    Node getServiceNode() { return serviceNode }

    @CompileStatic
    String getServiceName() { return (path ? path + "." : "") + verb + (noun ? "#" + noun : "") }
    @CompileStatic
    String getPath() { return path }
    @CompileStatic
    String getVerb() { return verb }
    @CompileStatic
    String getNoun() { return noun }

    @CompileStatic
    String getAuthenticate() { return internalAuthenticate }
    @CompileStatic
    String getServiceType() { return internalServiceType }
    @CompileStatic
    boolean getTxIgnore() { return internalTxIgnore }
    @CompileStatic
    boolean getTxForceNew() { return internalTxForceNew }
    @CompileStatic
    boolean getTxUseCache() { return internalTxUseCache }
    @CompileStatic
    Integer getTxTimeout() { return internalTransactionTimeout }

    @CompileStatic
    static String getPathFromName(String serviceName) {
        String p = serviceName
        // do hash first since a noun following hash may have dots in it
        if (p.contains("#")) p = p.substring(0, p.indexOf("#"))
        if (!p.contains(".")) return null
        return p.substring(0, p.lastIndexOf("."))
    }
    @CompileStatic
    static String getVerbFromName(String serviceName) {
        String v = serviceName
        // do hash first since a noun following hash may have dots in it
        if (v.contains("#")) v = v.substring(0, v.indexOf("#"))
        if (v.contains(".")) v = v.substring(v.lastIndexOf(".") + 1)
        return v
    }
    @CompileStatic
    static String getNounFromName(String serviceName) {
        if (!serviceName.contains("#")) return null
        return serviceName.substring(serviceName.lastIndexOf("#") + 1)
    }

    static final Map<String, String> verbAuthzActionIdMap = [create:'AUTHZA_CREATE', update:'AUTHZA_UPDATE',
            store:'AUTHZA_UPDATE', delete:'AUTHZA_DELETE', view:'AUTHZA_VIEW', find:'AUTHZA_VIEW']
    @CompileStatic
    static String getVerbAuthzActionId(String theVerb) {
        // default to require the "All" authz action, and for special verbs default to something more appropriate
        String authzAction = verbAuthzActionIdMap.get(theVerb)
        if (authzAction == null) authzAction = 'AUTHZA_ALL'
        return authzAction
    }

    @CompileStatic
    String getLocation() {
        // TODO: see if the location is an alias from the conf -> service-facade
        return serviceNode.attribute('location')
    }
    @CompileStatic
    String getMethod() { return serviceNode.attribute('method') }

    @CompileStatic
    XmlAction getXmlAction() { return xmlAction }

    Node getInParameter(String name) { return (Node) inParametersNode."parameter".find({ it."@name" == name }) }
    Set<String> getInParameterNames() {
        Set<String> inNames = new HashSet()
        for (Node parameter in inParametersNode."parameter") inNames.add((String) parameter."@name")
        return inNames
    }

    Node getOutParameter(String name) { return (Node) outParametersNode."parameter".find({ it."@name" == name }) }
    Set<String> getOutParameterNames() {
        Set<String> outNames = new HashSet()
        for (Node parameter in outParametersNode."parameter") outNames.add((String) parameter."@name")
        return outNames
    }

    @CompileStatic
    void convertValidateCleanParameters(Map<String, Object> parameters, ExecutionContextImpl eci) {
        // even if validate is false still apply defaults, convert defined params, etc
        checkParameterMap("", parameters, parameters, inParametersNode, serviceNode.attribute('validate') != "false", eci)
    }

    @CompileStatic
    protected void checkParameterMap(String namePrefix, Map<String, Object> rootParameters, Map parameters,
                                     Node parametersParentNode, boolean validate, ExecutionContextImpl eci) {
        Map<String, Node> parameterNodeMap = new HashMap<String, Node>()
        NodeList parameterNodeList = (NodeList) parametersParentNode.get("parameter")
        for (Object parameterObj in parameterNodeList) {
            Node parameter = (Node) parameterObj
            String name = (String) parameter.attribute('name')
            parameterNodeMap.put(name, parameter)
        }

        // if service is to be validated, go through service in-parameters definition and only get valid parameters
        // go through a set that is both the defined in-parameters and the keySet of passed in parameters
        Set<String> iterateSet = new HashSet(parameters.keySet())
        iterateSet.addAll(parameterNodeMap.keySet())
        for (String parameterName in iterateSet) {
            if (!parameterNodeMap.containsKey(parameterName)) {
                if (validate) {
                    parameters.remove(parameterName)
                    if (logger.traceEnabled && parameterName != "ec")
                        logger.trace("Parameter [${namePrefix}${parameterName}] was passed to service [${getServiceName()}] but is not defined as an in parameter, removing from parameters.")
                }
                // even if we are not validating, ie letting extra parameters fall through in this case, we don't want to do the type convert or anything
                continue
            }

            Node parameterNode = parameterNodeMap.get(parameterName)
            Object parameterValue = parameters.get(parameterName)
            String type = (String) parameterNode.attribute('type') ?: "String"

            // check type
            Object converted = checkConvertType(parameterNode, namePrefix, parameterName, parameterValue, rootParameters, eci)
            if (converted != null) {
                parameterValue = converted
                // put the final parameterValue back into the parameters Map
                parameters.put(parameterName, parameterValue)
            } else if (parameterValue) {
                // no type conversion? error time...
                if (validate) eci.message.addValidationError(null, "${namePrefix}${parameterName}", getServiceName(), "Field was type [${parameterValue?.class?.name}], expecting type [${type}]", null)
                continue
            }
            if (converted == null && !parameterValue && parameterValue != null && !StupidJavaUtilities.isInstanceOf(parameterValue, type)) {
                // we have an empty value of a different type, just set it to null
                parameterValue = null
                // put the final parameterValue back into the parameters Map
                parameters.put(parameterName, parameterValue)
            }

            if (validate && parameterNode.get("subtype")) checkSubtype(parameterName, parameterNode, parameterValue, eci)
            if (validate) validateParameterHtml(parameterNode, namePrefix, parameterName, parameterValue, eci)

            // do this after the convert so defaults are in place
            // check if required and empty - use groovy non-empty rules for String only
            if (StupidUtilities.isEmpty(parameterValue)) {
                if (validate && parameterNode.attribute('required') == "true") {
                    eci.message.addValidationError(null, "${namePrefix}${parameterName}", getServiceName(), "Field cannot be empty", null)
                }
                // NOTE: should we change empty values to null? for now, no
                // if it isn't there continue on since since default-value, etc are handled below
            }

            // do this after the convert so we can deal with objects when needed
            if (validate) validateParameter(parameterNode, parameterName, parameterValue, eci)

            // now check parameter sub-elements
            if (parameterValue instanceof Map) {
                // any parameter sub-nodes?
                if (parameterNode.get("parameter"))
                    checkParameterMap("${namePrefix}${parameterName}.", rootParameters, (Map) parameterValue, parameterNode, validate, eci)
            } else if (parameterValue instanceof Node) {
                if (parameterNode.get("parameter"))
                    checkParameterNode("${namePrefix}${parameterName}.", rootParameters, (Node) parameterValue, parameterNode, validate, eci)
            }
        }
    }

    protected void checkParameterNode(String namePrefix, Map<String, Object> rootParameters, Node nodeValue,
                                      Node parametersParentNode, boolean validate, ExecutionContextImpl eci) {
        // NOTE: don't worry about extra attributes or sub-Nodes... let them through

        // go through attributes of Node, validate each that corresponds to a parameter def
        for (Map.Entry attrEntry in nodeValue.attributes().entrySet()) {
            String parameterName = (String) attrEntry.getKey()
            Node parameterNode = (Node) parametersParentNode."parameter".find({ it."@name" == parameterName })
            if (parameterNode == null) {
                // NOTE: consider complaining here to not allow additional attributes, that could be annoying though so for now do not...
                continue
            }

            Object parameterValue = nodeValue.attribute(parameterName)

            // NOTE: required check is done later, now just validating the parameters seen
            // NOTE: no type conversion for Node attributes, they are always String

            if (validate) validateParameterHtml(parameterNode, namePrefix, parameterName, parameterValue, eci)

            // NOTE: only use the converted value for validation, attributes must be strings so can't put it back there
            Object converted = checkConvertType(parameterNode, namePrefix, parameterName, parameterValue, rootParameters, eci)
            if (validate) validateParameter(parameterNode, parameterName, converted, eci)

            // NOTE: no sub-nodes here, it's an attribute, so ignore child parameter elements
        }

        // go through sub-Nodes and if corresponds to
        // - Node parameter, checkParameterNode
        // - otherwise, check type/etc
        // TODO - parameter with type Map, convert to Map? ...checkParameterMap; converting to Map would kill multiple values, or they could be put in a List, though that pattern is a bit annoying...
        for (Node childNode in (Collection<Node>) nodeValue.children()) {
            String parameterName = childNode.name()
            Node parameterNode = (Node) parametersParentNode."parameter".find({ it."@name" == parameterName })
            if (parameterNode == null) {
                // NOTE: consider complaining here to not allow additional attributes, that could be annoying though so for now do not...
                continue
            }

            if (parameterNode."@type" == "Node" || parameterNode."@type" == "groovy.util.Node") {
                // recurse back into this method
                checkParameterNode("${namePrefix}${parameterName}.", rootParameters, childNode, parameterNode, validate, eci)
            } else {
                Object parameterValue = StupidUtilities.nodeText(childNode)

                // NOTE: required check is done later, now just validating the parameters seen
                // NOTE: no type conversion for Node attributes, they are always String

                if (validate) validateParameterHtml(parameterNode, namePrefix, parameterName, parameterValue, eci)

                // NOTE: only use the converted value for validation, attributes must be strings so can't put it back there
                Object converted = checkConvertType(parameterNode, namePrefix, parameterName, parameterValue, rootParameters, eci)
                if (validate) validateParameter(parameterNode, parameterName, converted, eci)
            }
        }

        // if there is text() under this node, use the _VALUE parameter node to validate
        Node textValueNode = (Node) parametersParentNode."parameter".find({ it."@name" == "_VALUE" })
        if (textValueNode != null) {
            Object parameterValue = StupidUtilities.nodeText(nodeValue)
            if (!parameterValue) {
                if (validate && textValueNode."@required" == "true") {
                    eci.message.addError("${namePrefix}_VALUE cannot be empty (service ${getServiceName()})")
                }
            } else {
                if (validate) validateParameterHtml(textValueNode, namePrefix, "_VALUE", parameterValue, eci)

                // NOTE: only use the converted value for validation, attributes must be strings so can't put it back there
                Object converted = checkConvertType(textValueNode, namePrefix, "_VALUE", parameterValue, rootParameters, eci)
                if (validate) validateParameter(textValueNode, "_VALUE", converted, eci)
            }
        }

        // check for missing parameters (no attribute or sub-Node) that are required
        for (Node parameterNode in parametersParentNode."parameter") {
            // skip _VALUE, checked above
            if (parameterNode."@name" == "_VALUE") continue

            if (parameterNode."@required" == "true") {
                String parameterName = parameterNode."@name"
                boolean valueFound = false
                if (nodeValue.attribute(parameterName)) {
                    valueFound = true
                } else {
                    for (Node childNode in (Collection<Node>) nodeValue.children()) {
                        if (childNode.localText()) {
                            valueFound = true
                            break
                        }
                    }
                }

                if (validate && !valueFound) eci.message.addValidationError(null, "${namePrefix}${parameterName}", getServiceName(), "Field cannot be empty", null)
            }
        }
    }

    @CompileStatic
    protected Object checkConvertType(Node parameterNode, String namePrefix, String parameterName, Object parameterValue,
                                      Map<String, Object> rootParameters, ExecutionContextImpl eci) {
        // set the default if applicable
        boolean parameterIsEmpty = StupidUtilities.isEmpty(parameterValue)
        String defaultStr = (String) parameterNode.attribute('default')
        if (parameterIsEmpty && defaultStr) {
            ((ContextStack) eci.context).push(rootParameters)
            parameterValue = eci.getResource().expression(defaultStr, "${this.location}_${parameterName}_default")
            // logger.warn("For parameter ${namePrefix}${parameterName} new value ${parameterValue} from default [${parameterNode.'@default'}] and context: ${eci.context}")
            ((ContextStack) eci.context).pop()
        }
        // set the default-value if applicable
        String defaultValueStr = (String) parameterNode.attribute('default-value')
        if (parameterIsEmpty && defaultValueStr) {
            ((ContextStack) eci.context).push(rootParameters)
            parameterValue = eci.getResource().expand(defaultValueStr, "${this.location}_${parameterName}_default_value")
            // logger.warn("For parameter ${namePrefix}${parameterName} new value ${parameterValue} from default-value [${parameterNode.'@default-value'}] and context: ${eci.context}")
            ((ContextStack) eci.context).pop()
        }

        // if null value, don't try to convert
        if (parameterValue == null) return null

        String type = (String) parameterNode.attribute('type') ?: "String"
        if (!StupidJavaUtilities.isInstanceOf(parameterValue, type)) {
            // do type conversion if possible
            String format = (String) parameterNode.attribute('format')
            Object converted = null
            boolean isString = parameterValue instanceof CharSequence
            boolean isEmptyString = isString && ((CharSequence) parameterValue).length() == 0
            if (isString && !isEmptyString) {
                // try some String to XYZ specific conversions for parsing with format, locale, etc
                switch (type) {
                    case "Integer":
                    case "java.lang.Integer":
                    case "Long":
                    case "java.lang.Long":
                    case "Float":
                    case "java.lang.Float":
                    case "Double":
                    case "java.lang.Double":
                    case "BigDecimal":
                    case "java.math.BigDecimal":
                    case "BigInteger":
                    case "java.math.BigInteger":
                        BigDecimal bdVal = eci.l10n.parseNumber((String) parameterValue, format)
                        if (bdVal == null) {
                            eci.message.addValidationError(null, "${namePrefix}${parameterName}", getServiceName(), "Value entered (${parameterValue}) could not be converted to a ${type}" + (format ? " using format [${format}]": ""), null)
                        } else {
                            converted = StupidUtilities.basicConvert(bdVal, type)
                        }
                        break
                    case "Time":
                    case "java.sql.Time":
                        converted = eci.l10n.parseTime((String) parameterValue, format)
                        if (converted == null) {
                            eci.message.addValidationError(null, "${namePrefix}${parameterName}", getServiceName(), "Value entered (${parameterValue}) could not be converted to a ${type}" + (format ? " using format [${format}]": ""), null)
                        }
                        break
                    case "Date":
                    case "java.sql.Date":
                        converted = eci.l10n.parseDate((String) parameterValue, format)
                        if (converted == null) {
                            eci.message.addValidationError(null, "${namePrefix}${parameterName}", getServiceName(), "Value entered (${parameterValue}) could not be converted to a ${type}" + (format ? " using format [${format}]": ""), null)
                        }
                        break
                    case "Timestamp":
                    case "java.sql.Timestamp":
                        converted = eci.l10n.parseTimestamp((String) parameterValue, format)
                        if ((Object) converted == null) {
                            eci.message.addValidationError(null, "${namePrefix}${parameterName}", getServiceName(), "Value entered (${parameterValue}) could not be converted to a ${type}" + (format ? " using format [${format}]": ""), null)
                        }
                        break
                    case "Collection":
                    case "List":
                    case "java.util.Collection":
                    case "java.util.List":
                        String valueStr = (String) parameterValue
                        // strip off square braces
                        if (valueStr.charAt(0) == ((char) '[') && valueStr.charAt(valueStr.length()-1) == ((char) ']'))
                            valueStr = valueStr.substring(1, valueStr.length()-1)
                        // split by comma or just create a list with the single string
                        if (valueStr.contains(",")) converted = Arrays.asList(valueStr.split(","))
                        else converted = [valueStr]
                        break
                    case "Set":
                    case "java.util.Set":
                        String valueStr = (String) parameterValue
                        // strip off square braces
                        if (valueStr.charAt(0) == ((char) '[') && valueStr.charAt(valueStr.length()-1) == ((char) ']'))
                            valueStr = valueStr.substring(1, valueStr.length()-1)
                        // split by comma or just create a list with the single string
                        if (valueStr.contains(",")) converted = new HashSet(valueStr.split(",").collect())
                        else converted = new HashSet([valueStr])
                        break
                }
            }

            // fallback to a really simple type conversion
            if (converted == null && !isEmptyString) converted = StupidUtilities.basicConvert(parameterValue, type)

            return converted
        }
        return parameterValue
    }

    @CompileStatic
    protected void validateParameterHtml(Node parameterNode, String namePrefix, String parameterName, Object parameterValue,
                                         ExecutionContextImpl eci) {
        // check for none/safe/any HTML
        boolean isString = parameterValue instanceof CharSequence
        String allowHtml = (String) parameterNode.attribute('allow-html')
        if ((isString || parameterValue instanceof List) && allowHtml != "any") {
            boolean allowSafe = (allowHtml == "safe")

            if (isString) {
                canonicalizeAndCheckHtml(parameterName, parameterValue.toString(), allowSafe, eci)
            } else {
                List lst = parameterValue as List
                List lstClone = new ArrayList(lst)
                lst.clear()
                for (Object obj in lstClone) {
                    if (obj instanceof CharSequence) {
                        lst.add(canonicalizeAndCheckHtml(parameterName, obj.toString(), allowSafe, eci))
                    } else {
                        lst.add(obj)
                    }
                }
            }
        }
    }

    @CompileStatic
    protected boolean validateParameter(Node vpNode, String parameterName, Object pv, ExecutionContextImpl eci) {
        // run through validations under parameter node

        // no validation done if value is empty, that should be checked with the required attribute only
        if (StupidUtilities.isEmpty(pv)) return true

        boolean allPass = true
        for (Node child in (Collection<Node>) vpNode.children()) {
            if (child.name() == "description" || child.name() == "subtype") continue
            // NOTE don't break on fail, we want to get a list of all failures for the user to see
            try {
                if (!validateParameterSingle(child, parameterName, pv, eci)) allPass = false
            } catch (Throwable t) {
                logger.error("Error in validation", t)
                eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${pv}) failed ${child.name()} validation: ${t.message}", null)
            }
        }
        return allPass
    }

    @CompileStatic
    protected boolean validateParameterSingle(Node valNode, String parameterName, Object pv, ExecutionContextImpl eci) {
        switch (valNode.name()) {
        case "val-or":
            boolean anyPass = false
            for (Node child in (Collection<Node>) valNode.children()) if (validateParameterSingle(child, parameterName, pv, eci)) anyPass = true
            return anyPass
        case "val-and":
            boolean allPass = true
            for (Node child in (Collection<Node>) valNode.children()) if (!validateParameterSingle(child, parameterName, pv, eci)) allPass = false
            return allPass
        case "val-not":
            // just in case there are multiple children treat like and, then not it
            boolean allPass = true
            for (Node child in (Collection<Node>) valNode.children()) if (!validateParameterSingle(child, parameterName, pv, eci)) allPass = false
            return !allPass
        case "matches":
            if (!(pv instanceof CharSequence)) {
                eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${pv}) is not a string, cannot do matches validation.", null)
                return false
            }
            String pvString = pv.toString()
            String regexp = (String) valNode.attribute('regexp')
            if (regexp && !pvString.matches(regexp)) {
                // a message attribute should always be there, but just in case we'll have a default
                eci.message.addValidationError(null, parameterName, getServiceName(), (String) (valNode.attribute('message') ?: "Value entered (${pv}) did not match expression: ${regexp}"), null)
                return false
            }
            return true
        case "number-range":
            // go to BigDecimal through String to get more accurate value
            BigDecimal bdVal = new BigDecimal(pv as String)
            String minStr = (String) valNode.attribute('min')
            if (minStr) {
                BigDecimal min = new BigDecimal(minStr)
                if (valNode.attribute('min-include-equals') == "false") {
                    if (bdVal <= min) {
                        eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${pv}) is less than or equal to ${min} must be greater than.", null)
                        return false
                    }
                } else {
                    if (bdVal < min) {
                        eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${pv}) is less than ${min} and must be greater than or equal to.", null)
                        return false
                    }
                }
            }
            String maxStr = (String) valNode.attribute('max')
            if (maxStr) {
                BigDecimal max = new BigDecimal(maxStr)
                if (valNode.attribute('max-include-equals') == "true") {
                    if (bdVal > max) {
                        eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${pv}) is greater than ${max} and must be less than or equal to.", null)
                        return false
                    }
                } else {
                    if (bdVal >= max) {
                        eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${pv}) is greater than or equal to ${max} and must be less than.", null)
                        return false
                    }
                }
            }
            return true
        case "number-integer":
            try {
                new BigInteger(pv as String)
            } catch (NumberFormatException e) {
                if (logger.isTraceEnabled()) logger.trace("Adding error message for NumberFormatException for BigInteger parse: ${e.toString()}")
                eci.message.addValidationError(null, parameterName, getServiceName(), "Value [${pv}] is not a whole (integer) number.", null)
                return false
            }
            return true
        case "number-decimal":
            try {
                new BigDecimal(pv as String)
            } catch (NumberFormatException e) {
                if (logger.isTraceEnabled()) logger.trace("Adding error message for NumberFormatException for BigDecimal parse: ${e.toString()}")
                eci.message.addValidationError(null, parameterName, getServiceName(), "Value [${pv}] is not a decimal number.", null)
                return false
            }
            return true
        case "text-length":
            String str = pv as String
            String minStr = (String) valNode.attribute('min')
            if (minStr) {
                int min = minStr as int
                if (str.length() < min) {
                    eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${pv}), length ${str.length()}, is shorter than ${minStr} characters.", null)
                    return false
                }
            }
            String maxStr = (String) valNode.attribute('max')
            if (maxStr) {
                int max = maxStr as int
                if (str.length() > max) {
                    eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${pv}), length ${str.length()}, is longer than ${maxStr} characters.", null)
                    return false
                }
            }
            return true
        case "text-email":
            String str = pv as String
            if (!emailValidator.isValid(str)) {
                eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${str}) is not a valid email address.", null)
                return false
            }
            return true
        case "text-url":
            String str = pv as String
            if (!urlValidator.isValid(str)) {
                eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${str}) is not a valid URL.", null)
                return false
            }
            return true
        case "text-letters":
            String str = pv as String
            for (char c in str.getChars()) {
                if (!Character.isLetter(c)) {
                    eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${str}) must have only letters.", null)
                    return false
                }
            }
            return true
        case "text-digits":
            String str = pv as String
            for (char c in str.getChars()) {
                if (!Character.isDigit(c)) {
                    eci.message.addValidationError(null, parameterName, getServiceName(), "Value [${str}] must have only digits.", null)
                    return false
                }
            }
            return true
        case "time-range":
            Calendar cal
            String format = valNode.attribute('format')
            if (pv instanceof CharSequence) {
                cal = eci.getL10n().parseDateTime(pv.toString(), format)
            } else {
                // try letting groovy convert it
                cal = Calendar.getInstance()
                // TODO: not sure if this will work: ((pv as java.util.Date).getTime())
                cal.setTimeInMillis((pv as Date).getTime())
            }
            String after = (String) valNode.attribute('after')
            if (after) {
                // handle after date/time/date-time depending on type of parameter, support "now" too
                Calendar compareCal
                if (after == "now") {
                    compareCal = Calendar.getInstance()
                    compareCal.setTimeInMillis(eci.user.nowTimestamp.time)
                } else {
                    compareCal = eci.l10n.parseDateTime(after, format)
                }
                if (!cal.after(compareCal)) {
                    eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${pv}) is before ${after}.", null)
                    return false
                }
            }
            String before = (String) valNode.attribute('before')
            if (before) {
                // handle after date/time/date-time depending on type of parameter, support "now" too
                Calendar compareCal
                if (before == "now") {
                    compareCal = Calendar.getInstance()
                    compareCal.setTimeInMillis(eci.user.nowTimestamp.time)
                } else {
                    compareCal = eci.l10n.parseDateTime(before, format)
                }
                if (!cal.before(compareCal)) {
                    eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${pv}) is after ${before}.", null)
                    return false
                }
            }
            return true
        case "credit-card":
            long creditCardTypes = 0
            String types = (String) valNode.attribute('types')
            if (types) {
                for (String cts in types.split(","))
                    creditCardTypes += creditCardTypeMap.get(cts.trim())
            } else {
                creditCardTypes = allCreditCards
            }
            CreditCardValidator ccv = new CreditCardValidator(creditCardTypes)
            String str = pv as String
            if (!ccv.isValid(str)) {
                eci.message.addValidationError(null, parameterName, getServiceName(), "Value entered (${str}) is not a valid credit card number.", null)
                return false
            }
            return true
        }
        // shouldn't get here, but just in case
        return true
    }

    @CompileStatic
    protected Object canonicalizeAndCheckHtml(String parameterName, String parameterValue, boolean allowSafe,
                                              ExecutionContextImpl eci) {
        Object value
        try {
            value = StupidWebUtilities.defaultWebEncoder.canonicalize(parameterValue, true)
        } catch (IntrusionException e) {
            eci.message.addValidationError(null, parameterName, getServiceName(), "Found character escaping (mixed or double) that is not allowed or other format consistency error: " + e.toString(), null)
            return parameterValue
        }

        if (allowSafe) {
            ValidationErrorList vel = new ValidationErrorList()
            value = StupidWebUtilities.defaultWebValidator.getValidSafeHTML(parameterName, value, Integer.MAX_VALUE, true, vel)
            for (ValidationException ve in vel.errors()) eci.message.addError(ve.message)
        } else {
            // check for "<", ">"; this will protect against HTML/JavaScript injection
            if (value.contains("<") || value.contains(">")) {
                eci.message.addValidationError(null, parameterName, getServiceName(), "Less-than (<) and greater-than (>) symbols are not allowed.", null)
            }
        }

        return value
    }

    protected void checkSubtype(String parameterName, Node typeParentNode, Object value, ExecutionContextImpl eci) {
        if (typeParentNode."subtype") {
            if (value instanceof Collection) {
                // just check the first value in the list
                if (((Collection) value).size() > 0) {
                    String subType = typeParentNode."subtype"[0]."@type"
                    Object subValue = ((Collection) value).iterator().next()
                    if (!StupidJavaUtilities.isInstanceOf(subValue, subType)) {
                        eci.message.addError("Parameter [${parameterName}] passed to service [${getServiceName()}] had a subtype [${subValue.class.name}], expecting subtype [${subType}]")
                    } else {
                        // try the next level down
                        checkSubtype(parameterName, (Node) typeParentNode."subtype"[0], subValue, eci)
                    }
                }
            } else if (value instanceof Map) {
                // for each subtype element check its name/type
                Map mapVal = value
                for (Node stNode in typeParentNode."subtype") {
                    String subName = stNode."@name"
                    String subType = stNode."@type"
                    if (!subName || !subType) continue

                    Object subValue = mapVal.get(subName)
                    if (!subValue) continue
                    if (!StupidJavaUtilities.isInstanceOf(subValue, subType)) {
                        eci.message.addError("Parameter [${parameterName}] passed to service [${getServiceName()}] had a subtype [${subValue.class.name}], expecting subtype [${subType}]")
                    } else {
                        // try the next level down
                        checkSubtype(parameterName, stNode, subValue, eci)
                    }
                }
            }
        }
    }

    static final Map<String, Long> creditCardTypeMap =
            [visa:CreditCardValidator.VISA, mastercard:CreditCardValidator.MASTERCARD,
                    amex:CreditCardValidator.AMEX, discover:CreditCardValidator.DISCOVER,
                    dinersclub:CreditCardValidator.DINERS]
    static final long allCreditCards = CreditCardValidator.VISA + CreditCardValidator.MASTERCARD +
            CreditCardValidator.AMEX + CreditCardValidator.DISCOVER + CreditCardValidator.DINERS
    // NOTE: removed with updated for Validator 1.4.0: enroute, jcb, solo, switch, visaelectron

    /* These are no longer needed, but keeping for reference:
    static class CreditCardVisa implements CreditCardValidator.CreditCardType {
        boolean matches(String cc) {
            return (((cc.length() == 16) || (cc.length() == 13)) && (cc.substring(0, 1).equals("4")))
        }
    }
    static class CreditCardMastercard implements CreditCardValidator.CreditCardType {
        boolean matches(String cc) {
            int firstDigit = Integer.parseInt(cc.substring(0, 1))
            int secondDigit = Integer.parseInt(cc.substring(1, 2))
            return ((cc.length() == 16) && (firstDigit == 5) && ((secondDigit >= 1) && (secondDigit <= 5)))
        }
    }
    static class CreditCardAmex implements CreditCardValidator.CreditCardType {
        boolean matches(String cc) {
            int firstDigit = Integer.parseInt(cc.substring(0, 1))
            int secondDigit = Integer.parseInt(cc.substring(1, 2))
            return ((cc.length() == 15) && (firstDigit == 3) && ((secondDigit == 4) || (secondDigit == 7)))
        }
    }
    static class CreditCardDiscover implements CreditCardValidator.CreditCardType {
        boolean matches(String cc) {
            String first4digs = cc.substring(0, 4)
            return ((cc.length() == 16) && (first4digs.equals("6011")))
        }
    }
    static class CreditCardEnroute implements CreditCardValidator.CreditCardType {
        boolean matches(String cc) {
            String first4digs = cc.substring(0, 4)
            return ((cc.length() == 15) && (first4digs.equals("2014") || first4digs.equals("2149")))
        }
    }
    static class CreditCardJcb implements CreditCardValidator.CreditCardType {
        boolean matches(String cc) {
            String first4digs = cc.substring(0, 4)
            return ((cc.length() == 16) &&
                (first4digs.equals("3088") || first4digs.equals("3096") || first4digs.equals("3112") ||
                    first4digs.equals("3158") || first4digs.equals("3337") || first4digs.equals("3528")))
        }
    }
    static class CreditCardSolo implements CreditCardValidator.CreditCardType {
        boolean matches(String cc) {
            String first4digs = cc.substring(0, 4)
            String first2digs = cc.substring(0, 2)
            return (((cc.length() == 16) || (cc.length() == 18) || (cc.length() == 19)) &&
                    (first2digs.equals("63") || first4digs.equals("6767")))
        }
    }
    static class CreditCardSwitch implements CreditCardValidator.CreditCardType {
        boolean matches(String cc) {
            String first4digs = cc.substring(0, 4)
            String first6digs = cc.substring(0, 6)
            return (((cc.length() == 16) || (cc.length() == 18) || (cc.length() == 19)) &&
                (first4digs.equals("4903") || first4digs.equals("4905") || first4digs.equals("4911") ||
                    first4digs.equals("4936") || first6digs.equals("564182") || first6digs.equals("633110") ||
                    first4digs.equals("6333") || first4digs.equals("6759")))
        }
    }
    static class CreditCardDinersClub implements CreditCardValidator.CreditCardType {
        boolean matches(String cc) {
            int firstDigit = Integer.parseInt(cc.substring(0, 1))
            int secondDigit = Integer.parseInt(cc.substring(1, 2))
            return ((cc.length() == 14) && (firstDigit == 3) && ((secondDigit == 0) || (secondDigit == 6) || (secondDigit == 8)))
        }
    }
    static class CreditCardVisaElectron implements CreditCardValidator.CreditCardType {
        boolean matches(String cc) {
            String first6digs = cc.substring(0, 6)
            String first4digs = cc.substring(0, 4)
            return ((cc.length() == 16) &&
                (first6digs.equals("417500") || first4digs.equals("4917") || first4digs.equals("4913") ||
                    first4digs.equals("4508") || first4digs.equals("4844") || first4digs.equals("4027")))
        }
    }
    */
}
