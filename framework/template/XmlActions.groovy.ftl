<#--
This software is in the public domain under CC0 1.0 Universal.

To the extent possible under law, the author(s) have dedicated all
copyright and related and neighboring rights to this software to the
public domain worldwide. This software is distributed without any
warranty.

You should have received a copy of the CC0 Public Domain Dedication
along with this software (see the LICENSE.md file). If not, see
<http://creativecommons.org/publicdomain/zero/1.0/>.
-->
import org.moqui.impl.StupidUtilities
// these are in the context by default: ExecutionContext ec, Map<String, Object> context, Map<String, Object> result
<#visit xmlActionsRoot/>

<#macro actions>
<#recurse/>
// make sure the last statement is not considered the return value
return;
</#macro>
<#macro "always-actions">
<#recurse/>
// make sure the last statement is not considered the return value
return;
</#macro>
<#macro "pre-actions">
<#recurse/>
// make sure the last statement is not considered the return value
return;
</#macro>
<#macro "row-actions">
<#recurse/>
// make sure the last statement is not considered the return value
return;
</#macro>

<#-- NOTE should we handle out-map?has_content and async!=false with a ServiceResultWaiter? -->
<#macro "service-call">
    <#assign handleResult = (.node["@out-map"]?has_content && (!.node["@async"]?has_content || .node["@async"] == "false"))>
    <#assign outAapAddToExisting = !.node["@out-map-add-to-existing"]?has_content || .node["@out-map-add-to-existing"] == "true">
    if (true) {
        <#if handleResult>def call_service_result = </#if>ec.service.<#if .node.@async?has_content && .node.@async != "false">async()<#else>sync()</#if>.name("${.node.@name}")<#if .node["@async"]?if_exists == "persist">.persist(true)</#if><#if .node["@multi"]?if_exists == "true">.multi(true)</#if><#if .node["@multi"]?if_exists == "parameter">.multi(ec.web?.requestParameters?._isMulti == "true")</#if><#if .node["@transaction"]?exists><#if .node["@transaction"] == "ignore">.ignoreTransaction(true)<#elseif .node["@transaction"] == "force-new" || .node["@transaction"] == "force-cache">.requireNewTransaction(true)<#elseif .node["@transaction"] == "cache" || .node["@transaction"] == "force-cache">.useTransactionCache(true)</#if></#if>
            <#if .node["@in-map"]?if_exists == "true">.parameters(context)<#elseif .node["@in-map"]?has_content && .node["@in-map"] != "false">.parameters(${.node["@in-map"]})</#if><#list .node["field-map"] as fieldMap>.parameter("${fieldMap["@field-name"]}",<#if fieldMap["@from"]?has_content>${fieldMap["@from"]}<#elseif fieldMap["@value"]?has_content>"""${fieldMap["@value"]}"""<#else>${fieldMap["@field-name"]}</#if>)</#list>.call()
        <#if handleResult>
            <#if outAapAddToExisting>
            if (${.node["@out-map"]} != null) {
                if (call_service_result) ${.node["@out-map"]}.putAll(call_service_result)
            } else {
            </#if>
                ${.node["@out-map"]} = call_service_result
            <#if outAapAddToExisting>
            }
            </#if>
        </#if>
        <#if (.node["@web-send-json-response"]?if_exists == "true")>
        ec.web.sendJsonResponse(call_service_result)
        <#elseif (.node["@web-send-json-response"]?has_content && .node["@web-send-json-response"] != "false")>
        ec.web.sendJsonResponse(ec.resource.expression("${.node["@web-send-json-response"]}", "", call_service_result))
        </#if>
        <#if (.node["@ignore-error"]?if_exists == "true")>
        if (ec.message.hasError()) {
            ec.logger.warn("Ignoring error running service ${.node.@name}: " + ec.message.getErrorsString())
            ec.message.clearErrors()
        }
        <#else>
        if (ec.message.hasError()) return
        </#if>
    }
</#macro>

<#macro "script"><#if .node["@location"]?has_content>ec.resource.script("${.node["@location"]}", null)</#if>
    // begin inline script
    ${.node}
    // end inline script
</#macro>

<#macro set>
    <#if .node["@set-if-empty"]?has_content && .node["@set-if-empty"] == "false">
    _temp_internal = <#if .node["@type"]?has_content>StupidUtilities.basicConvert</#if>(<#if .node["@from"]?has_content>${.node["@from"]}<#else>"""${.node.@value}"""</#if><#if .node["@default-value"]?has_content> ?: "${.node["@default-value"]}"</#if><#if .node["@type"]?has_content>, "${.node["@type"]}"</#if>)
    if (_temp_internal) ${.node["@field"]} = _temp_internal
    <#else>
    ${.node["@field"]} = <#if .node["@type"]?has_content>StupidUtilities.basicConvert</#if>(<#if .node["@from"]?has_content>${.node["@from"]}<#else>"""${.node["@value"]}"""</#if><#if .node["@default-value"]?has_content> ?: "${.node["@default-value"]}"</#if><#if .node["@type"]?has_content>, "${.node["@type"]}"</#if>)
    </#if>
</#macro>

<#macro "order-map-list">
    StupidUtilities.orderMapList(${.node["@list"]}, [<#list .node["order-by"] as ob>"${ob["@field-name"]}"<#if ob_has_next>, </#if></#list>])
</#macro>
<#macro "filter-map-list">
    if (${.node["@list"]}) {
    <#if .node["@to-list"]?has_content>
        ${.node["@to-list"]} = new ArrayList(${.node["@list"]})
        def _listToFilter = ${.node["@to-list"]}
    <#else>
        def _listToFilter = ${.node["@list"]}
    </#if>
    <#if .node["field-map"]?has_content>
        StupidUtilities.filterMapList(_listToFilter, [<#list .node["field-map"] as fm>"${fm["@field-name"]}":<#if fm["@value"]?has_content>"""${fm["@value"]}"""<#elseif fm["@from"]?has_content>${fm["@from"]}<#else>${fm["@field-name"]}</#if><#if fm_has_next>, </#if></#list>])
    </#if>
    <#list .node["date-filter"] as df>
        StupidUtilities.filterMapListByDate(_listToFilter, "${df["@from-field-name"]?default("fromDate")}", "${df["@thru-field-name"]?default("thruDate")}", <#if df["@valid-date"]?has_content>${df["@valid-date"]} ?: ec.user.nowTimestamp<#else>null</#if>, ${df["@ignore-if-empty"]?default("false")})
    </#list>
    }
</#macro>

<#macro "entity-sequenced-id-primary">
    ${.node["@value-field"]}.setSequencedIdPrimary()
</#macro>
<#macro "entity-sequenced-id-secondary">
    ${.node["@value-field"]}.setSequencedIdSecondary()
</#macro>
<#macro "entity-data">
    // TODO impl entity-data
</#macro>

<#-- =================== entity-find elements =================== -->

<#macro "entity-find-one">
    if (true) {
        org.moqui.entity.EntityValue find_one_result = ec.entity.find("${.node["@entity-name"]}")<#if .node["@cache"]?has_content>.useCache(${.node["@cache"]})</#if><#if .node["@for-update"]?has_content>.forUpdate(${.node["@for-update"]})</#if>
                <#if .node["@auto-field-map"]?if_exists == "true" || ((!.node["@auto-field-map"]?has_content) && (!.node["field-map"]?has_content))>.condition(context)</#if><#list .node["field-map"] as fieldMap>.condition("${fieldMap["@field-name"]}", <#if fieldMap["@from"]?has_content>${fieldMap["@from"]}<#elseif fieldMap["@value"]?has_content>"""${fieldMap["@value"]}"""<#else>${fieldMap["@field-name"]}</#if>)</#list><#list .node["select-field"] as sf>.selectField("${sf["@field-name"]}")</#list>.one()
        if (${.node["@value-field"]} instanceof Map && !(${.node["@value-field"]} instanceof org.moqui.entity.EntityValue)) {
            if (find_one_result) ${.node["@value-field"]}.putAll(find_one_result)
        } else {
            ${.node["@value-field"]} = find_one_result
        }
    }
</#macro>
<#macro "entity-find">
    <#assign useCache = (.node["@cache"]?if_exists == "true")>
    <#assign doPaginate = .node["search-form-inputs"]?has_content && !(.node["search-form-inputs"][0]["@paginate"]?if_exists == "false")>
    ${.node["@list"]}_xafind = ec.entity.find("${.node["@entity-name"]}")<#if .node["@cache"]?has_content>.useCache(${.node["@cache"]})</#if><#if .node["@for-update"]?has_content>.forUpdate(${.node["@for-update"]})</#if><#if .node["@distinct"]?has_content>.distinct(${.node["@distinct"]})</#if><#if .node["@offset"]?has_content>.offset(${.node["@offset"]})</#if><#if .node["@limit"]?has_content>.limit(${.node["@limit"]})</#if><#list .node["select-field"] as sf>.selectField('${sf["@field-name"]}')</#list><#list .node["order-by"] as ob>.orderBy("${ob["@field-name"]}")</#list>
            <#if !useCache><#list .node["date-filter"] as df>.condition(<#visit df/>)</#list></#if><#list .node["econdition"] as ecn>.condition(<#visit ecn/>)</#list><#list .node["econditions"] as ecs>.condition(<#visit ecs/>)</#list><#list .node["econdition-object"] as eco>.condition(<#visit eco/>)</#list>
    <#-- do having-econditions first, if present will disable cached query, used in search-form-inputs -->
    <#if .node["having-econditions"]?has_content>${.node["@list"]}_xafind<#list .node["having-econditions"]["*"] as havingCond>.havingCondition(<#visit havingCond/>)</#list>
    </#if>
    <#if .node["search-form-inputs"]?has_content><#assign sfiNode = .node["search-form-inputs"][0]>${.node["@list"]}_xafind.searchFormInputs("${sfiNode["@input-fields-map"]!""}", "${sfiNode["@default-order-by"]!("")}", ${sfiNode["@paginate"]!("true")})
    </#if>
    <#if .node["limit-range"]?has_content && !useCache>
    org.moqui.entity.EntityListIterator ${.node["@list"]}_xafind_eli = ${.node["@list"]}_xafind.iterator()
    ${.node["@list"]} = ${.node["@list"]}_xafind_eli.getPartialList(${.node["@start"]}, ${.node["@size"]}, true)
    <#elseif .node["limit-view"]?has_content && !useCache>
    org.moqui.entity.EntityListIterator ${.node["@list"]}_xafind_eli = ${.node["@list"]}_xafind.iterator()
    ${.node["@list"]} = ${.node["@list"]}_xafind_eli.getPartialList((${.node["@view-index"]} - 1) * ${.node["@view-size"]}, ${.node["@view-size"]}, true)
    <#elseif .node["use-iterator"]?has_content && !useCache>
    ${.node["@list"]} = ${.node["@list"]}_xafind.iterator()
    <#else>
        ${.node["@list"]} = ${.node["@list"]}_xafind.list()
        <#if useCache>
            <#list .node["date-filter"] as df>
                ${.node["@list"]} = ${.node["@list"]}.filterByDate("${df["@from-field-name"]?default("fromDate")}", "${df["@thru-field-name"]?default("thruDate")}", <#if df["@valid-date"]?has_content>${df["@valid-date"]} as java.sql.Timestamp<#else>null</#if>, ${df["@ignore-if-empty"]?default("false")})
            </#list>
            <#if doPaginate>
                <#-- get the Count after the date-filter, but before the limit/pagination filter -->
                ${.node["@list"]}Count = ${.node["@list"]}.size()
                ${.node["@list"]} = ${.node["@list"]}.filterByLimit("${sfiNode["@input-fields-map"]!""}", ${sfiNode["@paginate"]!("true")})
                <#-- get the PageIndex and PageSize after date-filter AND after limit filter -->
                ${.node["@list"]}PageIndex = ${.node["@list"]}.pageIndex
                ${.node["@list"]}PageSize = ${.node["@list"]}.pageSize
            </#if>
        </#if>
    </#if>
    <#if doPaginate>
        <#if !useCache>
            ${.node["@list"]}Count = ${.node["@list"]}_xafind.count()
            ${.node["@list"]}PageIndex = ${.node["@list"]}_xafind.pageIndex
            ${.node["@list"]}PageSize = ${.node["@list"]}_xafind.pageSize
        </#if>
        ${.node["@list"]}PageMaxIndex = ((BigDecimal) (${.node["@list"]}Count - 1)).divide(${.node["@list"]}PageSize, 0, BigDecimal.ROUND_DOWN) as int
        ${.node["@list"]}PageRangeLow = ${.node["@list"]}PageIndex * ${.node["@list"]}PageSize + 1
        ${.node["@list"]}PageRangeHigh = (${.node["@list"]}PageIndex * ${.node["@list"]}PageSize) + ${.node["@list"]}PageSize
        if (${.node["@list"]}PageRangeHigh > ${.node["@list"]}Count) ${.node["@list"]}PageRangeHigh = ${.node["@list"]}Count
    </#if>
</#macro>
<#macro "entity-find-count">
    ${.node["@count-field"]} = ec.entity.find("${.node["@entity-name"]}")<#if .node["@cache"]?has_content>.useCache(${.node["@cache"]})</#if><#if .node["@distinct"]?has_content>.distinct(${.node["@distinct"]})</#if><#list .node["select-field"] as sf>.selectField('${sf["@field-name"]}')</#list>
            <#list .node["date-filter"] as df>.condition(<#visit df/>)</#list><#list .node["econdition"] as ec>.condition(<#visit ec/>)</#list><#list .node["econditions"] as ecs>.condition(<#visit ecs/>)</#list><#list .node["econdition-object"] as eco>.condition(<#visit eco/>)</#list><#if .node["having-econditions"]?has_content><#list .node["having-econditions"]["*"] as havingCond>.havingCondition(<#visit havingCond/>)</#list></#if>.count()
</#macro>
<#-- =================== entity-find sub-elements =================== -->
<#macro "date-filter">(org.moqui.entity.EntityCondition) ec.entity.conditionFactory.makeConditionDate("${.node["@from-field-name"]!("fromDate")}", "${.node["@thru-field-name"]!("thruDate")}", <#if .node["@valid-date"]?has_content>${.node["@valid-date"]} as java.sql.Timestamp<#else>null</#if>, ${.node["@ignore-if-empty"]!("false")})</#macro>
<#macro "econdition">(org.moqui.entity.EntityCondition) ec.entity.conditionFactory.makeActionConditionDirect("${.node["@field-name"]}", "${.node["@operator"]?default("equals")}", ${.node["@from"]?default(.node["@field-name"])}, <#if .node["@value"]?has_content>"${.node["@value"]}"<#else>null</#if>, <#if .node["@to-field-name"]?has_content>"${.node["@to-field-name"]}"<#else>null</#if>, ${.node["@ignore-case"]?default("false")}, ${.node["@ignore-if-empty"]?default("false")}, ${.node["@or-null"]?default("false")}, "${.node["@ignore"]?default("false")}")</#macro>
<#macro "econditions">(org.moqui.entity.EntityCondition) ec.entity.conditionFactory.makeCondition([<#list .node?children as subCond><#visit subCond/><#if subCond_has_next>, </#if></#list>], org.moqui.impl.entity.EntityConditionFactoryImpl.getJoinOperator("${.node["@combine"]!"and"}"))</#macro>
<#macro "econdition-object">${.node["@field"]}</#macro>

<#-- =================== entity other elements =================== -->

<#macro "entity-find-related-one">    ${.node["@to-value-field"]} = ${.node["@value-field"]}?.findRelatedOne("${.node["@relationship-name"]}", ${.node["@cache"]?default("null")}, ${.node["@for-update"]?default("null")})
</#macro>
<#macro "entity-find-related">    ${.node["@list"]} = ${.node["@value-field"]}?.findRelated("${.node["@relationship-name"]}", ${.node["@map"]?default("null")}, ${.node["@order-by-list"]?default("null")}, ${.node["@cache"]?default("null")}, ${.node["@for-update"]?default("null")})
</#macro>

<#macro "entity-make-value">    ${.node["@value-field"]} = ec.entity.makeValue("${.node["@entity-name"]}")<#if .node["@map"]?has_content>
    ${.node["@value-field"]}.setFields(${.node["@map"]}, true, null, null)</#if>
</#macro>
<#macro "entity-create">    ${.node["@value-field"]}<#if .node["@or-update"]?has_content && .node["@or-update"] == "true">.createOrUpdate()<#else/>.create()</#if>
</#macro>
<#macro "entity-update">    ${.node["@value-field"]}.update()
</#macro>
<#macro "entity-delete">    ${.node["@value-field"]}.delete()
</#macro>
<#macro "entity-delete-related">    ${.node["@value-field"]}.deleteRelated("${.node["@relationship-name"]}")
</#macro>
<#macro "entity-delete-by-condition">    ec.entity.find("${.node["@entity-name"]}")
            <#list .node["date-filter"] as df>.condition(<#visit df/>)</#list><#list .node["econdition"] as ec>.condition(<#visit ec/>)</#list><#list .node["econditions"] as ecs>.condition(<#visit ecs/>)</#list><#list .node["econdition-object"] as eco>.condition(<#visit eco/>)</#list>.deleteAll()
</#macro>
<#macro "entity-set">    ${.node["@value-field"]}.setFields(${.node["@map"]?default("context")}, ${.node["@set-if-empty"]?default("false")}, ${.node["@prefix"]?default("null")}, <#if .node["@include"]?has_content && .node["@include"] == "pk">true<#elseif .node["@include"]?has_content && .node["@include"] == "nonpk"/>false<#else/>null</#if>)
</#macro>

<#macro break>    break
</#macro>
<#macro continue>    continue
</#macro>
<#macro iterate>
    <#if .node["@key"]?has_content>
    if (${.node["@list"]} instanceof Map) {
        ${.node["@entry"]}_index = 0
        for (def ${.node["@entry"]}Entry in ${.node["@list"]}.entrySet()) {
            ${.node["@entry"]} = ${.node["@entry"]}Entry.getValue()
            ${.node["@key"]} = ${.node["@entry"]}Entry.getKey()
            <#recurse/>
            ${.node["@entry"]}_index++
        }
    } else if (${.node["@list"]} instanceof Collection<Map.Entry>) {
        ${.node["@entry"]}_index = 0
        for (def ${.node["@entry"]}Entry in ${.node["@list"]}) {
            ${.node["@entry"]} = ${.node["@entry"]}Entry.getValue()
            ${.node["@key"]} = ${.node["@entry"]}Entry.getKey()
            <#recurse/>
            ${.node["@entry"]}_index++
        }
    } else {
    </#if>
        ${.node["@entry"]}_index = 0
        _${.node["@entry"]}Iterator = ${.node["@list"]}.iterator()
        while (_${.node["@entry"]}Iterator.hasNext()) {
            ${.node["@entry"]} = _${.node["@entry"]}Iterator.next()
            ${.node["@entry"]}_has_next = _${.node["@entry"]}Iterator.hasNext()
            <#recurse/>
            ${.node["@entry"]}_index++
        }
        if (${.node["@list"]} instanceof org.moqui.entity.EntityListIterator) ${.node["@list"]}.close()
    <#if .node["@key"]?has_content>}</#if>
</#macro>
<#macro message><#if .node["@error"]?has_content && .node["@error"] == "true">    ec.message.addError("""${.node?trim}""")<#else/>    ec.message.addMessage("""${.node?trim}""")</#if>
</#macro>
<#macro "check-errors">    if (ec.message.errors) return
</#macro>

<#-- NOTE: if there is an error message (in ec.messages.errors) then the actions result is an error, otherwise it is not, so we need a default error message here -->
<#macro return><#assign returnMessage = .node["@message"]?default("")/><#if .node["@error"]?has_content && .node["@error"] == "true">    ec.message.addError("""${returnMessage?trim}""" ?: "Error in actions")<#else/>    if (returnMessage) ec.message.addMessage("""${returnMessage?trim}""")</#if>
    return
</#macro>
<#macro assert><#list .node["*"] as childCond>
    if (!(<#visit childCond/>)) ec.message.addError("""<#if .node["@title"]?has_content>[${.node["@title"]}] </#if> Assert failed: <#visit childCond/>""")</#list>
</#macro>

<#macro "xml-consume">
    // TODO impl xml-consume
</#macro>
<#macro "xml-consume-element">
    // TODO impl xml-consume-element
</#macro>
<#macro "xml-produce">
    // TODO impl xml-produce
</#macro>
<#macro "xml-produce-element">
    // TODO impl xml-produce-element
</#macro>

<#macro if>    if (<#if .node["@condition"]?has_content>${.node["@condition"]}</#if><#if .node["@condition"]?has_content && .node["condition"]?has_content> && </#if><#if .node["condition"]?has_content><#recurse .node["condition"][0]/></#if>) {
        <#recurse .node/><#if .node["then"]?has_content>
        <#recurse .node["then"][0]/></#if>
    }<#if .node["else-if"]?has_content><#list .node["else-if"] as elseIf> else if (<#if elseIf["@condition"]?has_content>${elseIf["@condition"]}</#if><#if elseIf["@condition"]?has_content && elseIf["condition"]?has_content> && </#if><#if elseIf["condition"]?has_content><#recurse elseIf["condition"][0]/></#if>) {
        <#recurse elseIf/><#if elseIf.then?has_content>
        <#recurse elseIf["then"][0]/></#if>
    }</#list></#if><#if .node["else"]?has_content> else {
        <#recurse .node["else"][0]/>
    }</#if>

</#macro>

<#macro while>    while (<#if .node.@condition?has_content>${.node.@condition}</#if><#if .node["@condition"]?has_content && .node["condition"]?has_content> && </#if><#if .node["condition"]?has_content><#recurse .node["condition"][0]/></#if>) {
        <#recurse .node/>
    }

</#macro>

<#-- =================== if/when sub-elements =================== -->

<#macro condition><#-- do nothing when visiting, only used explicitly inline --></#macro>
<#macro then><#-- do nothing when visiting, only used explicitly inline --></#macro>
<#macro "else-if"><#-- do nothing when visiting, only used explicitly inline --></#macro>
<#macro else><#-- do nothing when visiting, only used explicitly inline --></#macro>

<#macro or>(<#list .node.children as childNode><#visit childNode/><#if childNode_has_next> || </#if></#list>)</#macro>
<#macro and>(<#list .node.children as childNode><#visit childNode/><#if childNode_has_next> && </#if></#list>)</#macro>
<#macro not>!<#visit .node.children[0]/></#macro>

<#macro "compare">    <#if (.node?size > 0)>if (StupidUtilities.compare(${.node["@field"]}, <#if .node["@operator"]?has_content>"${.node["@operator"]}"<#else/>"equals"</#if>, <#if .node["@value"]?has_content>"""${.node["@value"]}"""<#else/>null</#if>, <#if .node["@to-field"]?has_content>${.node["@to-field"]}<#else/>null</#if>, <#if .node["@format"]?has_content>"${.node["@format"]}"<#else/>null</#if>, <#if .node["@type"]?has_content>"${.node["@type"]}"<#else/>"Object"</#if>)) {
        <#recurse .node/>
    }<#if .node.else?has_content> else {
        <#recurse .node.else[0]/>
    }</#if>
    <#else/>StupidUtilities.compare(${.node["@field"]}, <#if .node["@operator"]?has_content>"${.node["@operator"]}"<#else/>"equals"</#if>, <#if .node["@value"]?has_content>"""${.node["@value"]}"""<#else/>null</#if>, <#if .node["@to-field"]?has_content>${.node["@to-field"]}<#else/>null</#if>, <#if .node["@format"]?has_content>"${.node["@format"]}"<#else/>null</#if>, <#if .node["@type"]?has_content>"${.node["@type"]}"<#else/>"Object"</#if>)</#if>
</#macro>
<#macro "expression">${.node}
</#macro>

<#-- =================== other elements =================== -->

<#macro "log">    ec.logger.log(<#if .node["@level"]?has_content>"${.node["@level"]}"<#else/>"info"</#if>, """${.node["@message"]}""", null)
</#macro>
