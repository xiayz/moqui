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
import org.moqui.context.ArtifactTarpitException
import org.moqui.impl.entity.EntityValueBase

import java.sql.Timestamp

import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactExecutionFacade
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.Cache
import org.moqui.entity.EntityList
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.entity.EntityCondition

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
public class ArtifactExecutionFacadeImpl implements ArtifactExecutionFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ArtifactExecutionFacadeImpl.class)

    // NOTE: these need to be in a Map instead of the DB because Enumeration records may not yet be loaded
    final static Map<String, String> artifactTypeDescriptionMap = [AT_XML_SCREEN:"Screen",
            AT_XML_SCREEN_TRANS:"Transition", AT_SERVICE:"Service", AT_ENTITY:"Entity"]
    final static Map<String, String> artifactActionDescriptionMap = [AUTHZA_VIEW:"View",
            AUTHZA_CREATE:"Create", AUTHZA_UPDATE:"Update", AUTHZA_DELETE:"Delete", AUTHZA_ALL:"All"]

    protected ExecutionContextImpl eci
    protected Deque<ArtifactExecutionInfoImpl> artifactExecutionInfoStack = new LinkedList<ArtifactExecutionInfoImpl>()
    protected List<ArtifactExecutionInfoImpl> artifactExecutionInfoHistory = new LinkedList<ArtifactExecutionInfoImpl>()

    // NOTE: there is no code to clean out old entries in tarpitHitCache, using the cache idle expire time for that
    protected Cache tarpitHitCache
    protected Map<String, Boolean> artifactTypeAuthzEnabled = new HashMap()
    protected Map<String, Boolean> artifactTypeTarpitEnabled = new HashMap()
    protected EntityCondition nameIsPatternEqualsY
    // this is used by ScreenUrlInfo.isPermitted() which is called a lot, but that is transient so put here to have one per EC instance
    Map<String, Boolean> screenPermittedCache = [:]

    protected boolean authzDisabled = false
    protected boolean entityEcaDisabled = false

    ArtifactExecutionFacadeImpl(ExecutionContextImpl eci) {
        this.eci = eci
        this.tarpitHitCache = eci.cache.getCache("artifact.tarpit.hits")
        nameIsPatternEqualsY = eci.getEntity().getConditionFactory().makeCondition("nameIsPattern", ComparisonOperator.EQUALS, "Y")
    }

    boolean isAuthzEnabled(String artifactTypeEnumId) {
        Boolean en = artifactTypeAuthzEnabled.get(artifactTypeEnumId)
        if (en == null) {
            Node aeNode = eci.getEcfi().getArtifactExecutionNode(artifactTypeEnumId)
            en = aeNode != null ? !(aeNode.attribute('authz-enabled') == "false") : true
            artifactTypeAuthzEnabled.put(artifactTypeEnumId, en)
        }
        return en
    }
    boolean isTarpitEnabled(String artifactTypeEnumId) {
        Boolean en = artifactTypeTarpitEnabled.get(artifactTypeEnumId)
        if (en == null) {
            Node aeNode = eci.getEcfi().getArtifactExecutionNode(artifactTypeEnumId)
            en = aeNode != null ? !(aeNode.attribute('tarpit-enabled') == "false") : true
            artifactTypeTarpitEnabled.put(artifactTypeEnumId, en)
        }
        return en
    }

    @Override
    ArtifactExecutionInfo peek() { return this.artifactExecutionInfoStack.peekFirst() }

    @Override
    ArtifactExecutionInfo pop() {
        if (this.artifactExecutionInfoStack.size() > 0) {
            ArtifactExecutionInfoImpl lastAeii = artifactExecutionInfoStack.removeFirst()
            lastAeii.setEndTime()
            return lastAeii
        } else {
            logger.warn("Tried to pop from an empty ArtifactExecutionInfo stack", new Exception("Bad pop location"))
            return null
        }
    }

    @Override
    void push(String name, String typeEnumId, String actionEnumId, boolean requiresAuthz) {
        push(new ArtifactExecutionInfoImpl(name, typeEnumId, actionEnumId), requiresAuthz)
    }
    @Override
    void push(ArtifactExecutionInfo aei, boolean requiresAuthz) {
        ArtifactExecutionInfoImpl aeii = (ArtifactExecutionInfoImpl) aei
        // do permission check for this new aei that current user is trying to access
        String username = eci.getUser().getUsername()

        ArtifactExecutionInfoImpl lastAeii = artifactExecutionInfoStack.peekFirst()

        // always do this regardless of the authz checks, etc; keep a history of artifacts run
        if (lastAeii != null) { lastAeii.addChild(aeii); aeii.setParent(lastAeii) }
        else artifactExecutionInfoHistory.add(aeii)

        // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact push ${username} - ${aeii}")

        if (!isPermitted(username, aeii, lastAeii, requiresAuthz, true, eci.getUser().getNowTimestamp())) {
            Exception e = new ArtifactAuthorizationException("User [${username}] is not authorized for ${artifactActionDescriptionMap.get(aeii.getActionEnumId())} on ${artifactTypeDescriptionMap.get(aeii.getTypeEnumId())?:aeii.getTypeEnumId()} [${aeii.getName()}]")
            logger.warn("Artifact authorization failed", e)
            throw e
        }

        // NOTE: if needed the isPermitted method will set additional info in aeii
        this.artifactExecutionInfoStack.addFirst(aeii)
    }

    @Override
    Deque<ArtifactExecutionInfo> getStack() { return this.artifactExecutionInfoStack }
    String getStackNameString() {
        StringBuilder sb = new StringBuilder()
        Iterator i = this.artifactExecutionInfoStack.iterator()
        while (i.hasNext()) {
            ArtifactExecutionInfo aei = i.next()
            sb.append(aei.name)
            if (i.hasNext()) sb.append(',')
        }
        return sb.toString()
    }
    @Override
    List<ArtifactExecutionInfo> getHistory() { return this.artifactExecutionInfoHistory }

    String printHistory() {
        StringWriter sw = new StringWriter()
        for (ArtifactExecutionInfo aei in artifactExecutionInfoHistory) aei.print(sw, 0, true)
        return sw.toString()
    }

    void logProfilingDetail() {
        if (!logger.isInfoEnabled()) return

        StringWriter sw = new StringWriter()
        sw.append("========= Hot Spots by Own Time =========\n")
        sw.append("[{time}:{timeMin}:{timeAvg}:{timeMax}][{count}] {type} {action} {actionDetail} {name}\n")
        List<Map> ownHotSpotList = ArtifactExecutionInfoImpl.hotSpotByTime(artifactExecutionInfoHistory, true, "-time")
        ArtifactExecutionInfoImpl.printHotSpotList(sw, ownHotSpotList)
        logger.info(sw.toString())

        sw = new StringWriter()
        sw.append("========= Hot Spots by Total Time =========\n")
        sw.append("[{time}:{timeMin}:{timeAvg}:{timeMax}][{count}] {type} {action} {actionDetail} {name}\n")
        List<Map> totalHotSpotList = ArtifactExecutionInfoImpl.hotSpotByTime(artifactExecutionInfoHistory, false, "-time")
        ArtifactExecutionInfoImpl.printHotSpotList(sw, totalHotSpotList)
        logger.info(sw.toString())

        /* leave this out by default, sometimes interesting, but big
        sw = new StringWriter()
        sw.append("========= Consolidated Artifact List =========\n")
        sw.append("[{time}:{thisTime}:{childrenTime}][{count}] {type} {action} {actionDetail} {name}\n")
        List<Map> consolidatedList = ArtifactExecutionInfoImpl.consolidateArtifactInfo(artifactExecutionInfoHistory)
        ArtifactExecutionInfoImpl.printArtifactInfoList(sw, consolidatedList, 0)
        logger.info(sw.toString())
        */
    }


    void setAnonymousAuthorizedAll() {
        ArtifactExecutionInfoImpl aeii = artifactExecutionInfoStack.peekFirst()
        aeii.authorizationInheritable = true
        aeii.authorizedUserId = eci.getUser().getUserId() ?: "_NA_"
        if (aeii.authorizedAuthzTypeId != "AUTHZT_ALWAYS") aeii.authorizedAuthzTypeId = "AUTHZT_ALLOW"
        aeii.authorizedActionEnumId = "AUTHZA_ALL"
    }

    void setAnonymousAuthorizedView() {
        ArtifactExecutionInfoImpl aeii = artifactExecutionInfoStack.peekFirst()
        aeii.authorizationInheritable = true
        aeii.authorizedUserId = eci.getUser().getUserId() ?: "_NA_"
        if (aeii.authorizedAuthzTypeId != "AUTHZT_ALWAYS") aeii.authorizedAuthzTypeId = "AUTHZT_ALLOW"
        if (aeii.authorizedActionEnumId != "AUTHZA_ALL") aeii.authorizedActionEnumId = "AUTHZA_VIEW"
    }

    boolean disableAuthz() { boolean alreadyDisabled = this.authzDisabled; this.authzDisabled = true; return alreadyDisabled }
    void enableAuthz() { this.authzDisabled = false }
    boolean getAuthzDisabled() { return authzDisabled }

    boolean disableEntityEca() { boolean alreadyDisabled = this.entityEcaDisabled; this.entityEcaDisabled = true; return alreadyDisabled }
    void enableEntityEca() { this.entityEcaDisabled = false }
    boolean entityEcaDisabled() { return this.entityEcaDisabled }

    /** Checks to see if username is permitted to access given resource.
     *
     * @param username
     * @param resourceAccess Formatted as: "${typeEnumId}:${actionEnumId}:${name}"
     * @param nowTimestamp
     * @param eci
     * @return
     */
    static boolean isPermitted(String username, String resourceAccess, Timestamp nowTimestamp, ExecutionContextImpl eci) {
        int firstColon = resourceAccess.indexOf(":")
        int secondColon = resourceAccess.indexOf(":", firstColon+1)
        if (firstColon == -1 || secondColon == -1) throw new ArtifactAuthorizationException("Resource access string does not have two colons (':'), must be formatted like: \"\${typeEnumId}:\${actionEnumId}:\${name}\"")

        String typeEnumId = resourceAccess.substring(0, firstColon)
        String actionEnumId = resourceAccess.substring(firstColon+1, secondColon)
        String name = resourceAccess.substring(secondColon+1)

        return eci.artifactExecutionFacade.isPermitted(username,
                new ArtifactExecutionInfoImpl(name, typeEnumId, actionEnumId), null, true, true, nowTimestamp)
    }

    boolean isPermitted(String userId, ArtifactExecutionInfoImpl aeii, ArtifactExecutionInfoImpl lastAeii,
                        boolean requiresAuthz, boolean countTarpit, Timestamp nowTimestamp) {

        // never do this for entities when disableAuthz, as we might use any below and would cause infinite recursion
        // for performance reasons if this is an entity and no authz required don't bother looking at tarpit, checking for deny/etc
        if ((!requiresAuthz || this.authzDisabled) && aeii.getTypeEnumId() == 'AT_ENTITY') {
            if (lastAeii != null && lastAeii.authorizationInheritable) aeii.copyAuthorizedInfo(lastAeii)
            return true
        }

        // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact isPermitted after authzDisabled ${aeii}")

        EntityFacadeImpl efi = (EntityFacadeImpl) eci.getEntity()
        UserFacadeImpl ufi = (UserFacadeImpl) eci.getUser()


        boolean alreadyDisabled = disableAuthz()
        try {
            // see if there is a UserAccount for the username, and if so get its userId as a more permanent identifier
            EntityValue ua = ufi.getUserAccount()
            if (ua) userId = ua.userId

            if (countTarpit && isTarpitEnabled(aeii.getTypeEnumId())) {
                // record and check velocity limit (tarpit)
                boolean recordHitTime = false
                long lockForSeconds = 0
                long checkTime = System.currentTimeMillis()
                List<EntityValue> artifactTarpitCheckList = null
                // only check screens if they are the final screen in the chain (the target screen)
                if (aeii.getTypeEnumId() != 'AT_XML_SCREEN' || requiresAuthz) {
                    EntityList fullList = ufi.getArtifactTarpitCheckList()
                    if (fullList) {
                        artifactTarpitCheckList = new LinkedList()
                        for (EntityValue atEv in fullList) if (atEv.get('artifactTypeEnumId') == aeii.getTypeEnumId())
                            artifactTarpitCheckList.add(atEv)
                    }
                }
                // if (aeii.getTypeEnumId() == "AT_XML_SCREEN") logger.warn("TOREMOVE about to check tarpit [${tarpitKey}], userGroupIdSet=${userGroupIdSet}, artifactTarpitList=${artifactTarpitList}")
                if (artifactTarpitCheckList) {
                    String tarpitKey = userId + '@' + aeii.getTypeEnumId() + ':' + aeii.getName()
                    List<Long> hitTimeList = null
                    for (EntityValue artifactTarpit in artifactTarpitCheckList) {
                        if (('Y'.equals(artifactTarpit.get('nameIsPattern')) &&
                                aeii.getName().matches((String) artifactTarpit.get('artifactName'))) ||
                                aeii.getName().equals(artifactTarpit.get('artifactName'))) {
                            recordHitTime = true
                            if (hitTimeList == null) hitTimeList = (List<Long>) tarpitHitCache.get(tarpitKey)
                            long maxHitsDuration = artifactTarpit.getLong('maxHitsDuration')
                            // count hits in this duration; start with 1 to count the current hit
                            long hitsInDuration = 1
                            if (hitTimeList) {
                                // copy the list to avoid a ConcurrentModificationException
                                // TODO: a better approach to concurrency that won't ever miss hits would be better
                                List<Long> hitTimeListCopy = new ArrayList(hitTimeList)
                                for (Long hitTime in hitTimeListCopy) if ((hitTime - checkTime) < maxHitsDuration) hitsInDuration++
                            }
                            // logger.warn("TOREMOVE artifact [${tarpitKey}], now has ${hitsInDuration} hits in ${maxHitsDuration} seconds")
                            if (hitsInDuration > artifactTarpit.getLong('maxHitsCount') && artifactTarpit.getLong('tarpitDuration') > lockForSeconds) {
                                lockForSeconds = artifactTarpit.getLong('tarpitDuration')
                                logger.warn("User [${userId}] exceeded ${artifactTarpit.maxHitsCount} in ${maxHitsDuration} seconds for artifact [${tarpitKey}], locking for ${lockForSeconds} seconds")
                            }
                        }
                    }
                    if (recordHitTime) {
                        if (hitTimeList == null) { hitTimeList = new LinkedList<Long>(); tarpitHitCache.put(tarpitKey, hitTimeList) }
                        hitTimeList.add(System.currentTimeMillis())
                        // logger.warn("TOREMOVE recorded hit time for [${tarpitKey}], now has ${hitTimeList.size()} hits")

                        // check the ArtifactTarpitLock for the current artifact attempt before seeing if there is a new lock to create
                        // NOTE: this only runs if we are recording a hit time for an artifact, so no performance impact otherwise
                        EntityList tarpitLockList = efi.find('moqui.security.ArtifactTarpitLock')
                                .condition([userId:userId, artifactName:aeii.getName(), artifactTypeEnumId:aeii.getTypeEnumId()] as Map<String, Object>)
                                .useCache(true).list()
                                .filterByCondition(efi.getConditionFactory().makeCondition('releaseDateTime', ComparisonOperator.GREATER_THAN, ufi.getNowTimestamp()), true)
                        if (tarpitLockList) {
                            Timestamp releaseDateTime = tarpitLockList.first.getTimestamp('releaseDateTime')
                            int retryAfterSeconds = ((releaseDateTime.getTime() - System.currentTimeMillis())/1000).intValue()
                            throw new ArtifactTarpitException("User [${userId}] has accessed ${artifactTypeDescriptionMap.get(aeii.getTypeEnumId())?:aeii.getTypeEnumId()} [${aeii.getName()}] too many times and may not again until ${releaseDateTime} (retry after ${retryAfterSeconds} seconds)".toString(), retryAfterSeconds)
                        }
                    }
                    // record the tarpit lock
                    if (lockForSeconds > 0) {
                        eci.getService().sync().name('create', 'moqui.security.ArtifactTarpitLock').parameters(
                                [userId:userId, artifactName:aeii.getName(), artifactTypeEnumId:aeii.getTypeEnumId(),
                                releaseDateTime:(new Timestamp(checkTime + ((lockForSeconds as BigDecimal) * 1000).intValue()))]).call()
                        tarpitHitCache.remove(tarpitKey)
                    }
                }
            }
        } finally {
            if (!alreadyDisabled) enableAuthz()
        }

        // tarpit enabled already checked, if authz not enabled return true immediately
        if (!isAuthzEnabled(aeii.getTypeEnumId())) {
            // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO")) logger.warn("TOREMOVE artifact isPermitted authz disabled - ${aeii}")
            return true
        }

        // if last was an always allow, then don't bother checking for deny/etc
        if (lastAeii != null && lastAeii.isAuthorizationInheritable() && lastAeii.getAuthorizedUserId() == userId &&
                'AUTHZT_ALWAYS'.equals(lastAeii.getAuthorizedAuthzTypeId()) &&
                ('AUTHZA_ALL'.equals(lastAeii.getAuthorizedActionEnumId()) || aeii.getActionEnumId().equals(lastAeii.getAuthorizedActionEnumId()))) {
            aeii.copyAuthorizedInfo(lastAeii)
            // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO")) logger.warn("TOREMOVE artifact isPermitted already authorized for user ${userId} - ${aeii}")
            return true
        }

        EntityValue denyAacv = null

        // don't check authz for these queries, would cause infinite recursion
        alreadyDisabled = disableAuthz()
        try {
            /*
            // The old way: one big cached query:
            Set<String> userGroupIdSet = ufi.getUserGroupIdSet()
            EntityFind aacvFind = eci.entity.find("moqui.security.ArtifactAuthzCheckView")
                    .condition("artifactTypeEnumId", aeii.typeEnumId)
                    .condition(eci.entity.conditionFactory.makeCondition(
                        eci.entity.conditionFactory.makeCondition("artifactName", ComparisonOperator.EQUALS, aeii.name),
                        JoinOperator.OR,
                        eci.entity.conditionFactory.makeCondition("nameIsPattern", ComparisonOperator.EQUALS, "Y")))
            if (userGroupIdSet.size() == 1) aacvFind.condition("userGroupId", userGroupIdSet.iterator().next())
            else aacvFind.condition("userGroupId", ComparisonOperator.IN, userGroupIdSet)
            if (aeii.actionEnumId) aacvFind.condition("authzActionEnumId", ComparisonOperator.IN, [aeii.actionEnumId, "AUTHZA_ALL"])
            else aacvFind.condition("authzActionEnumId", "AUTHZA_ALL")

            aacvList = aacvFind.useCache(true).list()
            */

            // The new way: get a basic list by UserGroups from the UserFacadeImpl, then filter it down

            /* use custom logic here instead of EntityList.filterByCondition, this is run a lot so make it more efficient:
            EntityConditionFactory ecf = efi.getConditionFactory()
            EntityCondition aacvCond = ecf.makeCondition([
                        ecf.makeCondition("artifactTypeEnumId", ComparisonOperator.EQUALS, aeii.getTypeEnumId()),
                        ecf.makeCondition("authzActionEnumId", ComparisonOperator.IN, ["AUTHZA_ALL", aeii.getActionEnumId()]),
                        ecf.makeCondition(
                            ecf.makeCondition("artifactName", ComparisonOperator.EQUALS, aeii.getName()),
                            JoinOperator.OR, nameIsPatternEqualsY)])
            EntityList aacvList = ufi.getArtifactAuthzCheckList().cloneList()
            // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO")) logger.warn("TOREMOVE artifact isPermitted aacvList before filter: ${aacvList}")
            aacvList = aacvList.filterByCondition(aacvCond, true)
            */

            EntityList origAacvList = ufi.getArtifactAuthzCheckList()
            List<EntityValue> aacvList = []
            for (EntityValue aacv in origAacvList) {
                Map aacvMap = ((EntityValueBase) aacv).getValueMap()
                if (aacvMap.get('artifactTypeEnumId') == aeii.getTypeEnumId() &&
                        (aacvMap.get('authzActionEnumId') == 'AUTHZA_ALL' ||  aacvMap.get('authzActionEnumId') == aeii.getActionEnumId()) &&
                        (aacvMap.get('nameIsPattern') == 'Y' || aacvMap.get('artifactName') == aeii.getName())) {
                    aacvList.add(aacv)
                }
            }

            // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO")) logger.warn("TOREMOVE for aeii [${aeii}] artifact isPermitted aacvList: ${aacvList}; aacvCond: ${aacvCond}")

            if (aacvList.size() > 0) {
                for (EntityValue aacv in aacvList) {
                    // check the name
                    if ('Y'.equals(aacv.get('nameIsPattern')) && !aeii.getName().matches(aacv.getString('artifactName')))
                        continue
                    // check the filterMap
                    if (aacv.get('filterMap') && aeii.parameters) {
                        Map<String, Object> filterMapObj = (Map<String, Object>) eci.getResource().expression(aacv.getString('filterMap'), null)
                        boolean allMatches = true
                        for (Map.Entry<String, Object> filterEntry in filterMapObj.entrySet()) {
                            if (filterEntry.getValue() != aeii.parameters.get(filterEntry.getKey())) allMatches = false
                        }
                        if (!allMatches) continue
                    }
                    // check the record-level permission
                    if (aacv.get('viewEntityName')) {
                        EntityValue artifactAuthzRecord = efi.find('moqui.security.ArtifactAuthzRecord')
                                .condition('artifactAuthzId', aacv.get('artifactAuthzId')).useCache(true).one()
                        EntityDefinition ed = efi.getEntityDefinition((String) aacv.get('viewEntityName'))
                        EntityFind ef = efi.find((String) aacv.get('viewEntityName'))
                        if (artifactAuthzRecord.userIdField) {
                            ef.condition((String) artifactAuthzRecord.get('userIdField'), userId)
                        } else if (ed.isField('userId')) {
                            ef.condition('userId', userId)
                        }
                        if (artifactAuthzRecord.filterByDate == 'Y') {
                            ef.conditionDate((String) artifactAuthzRecord.get('filterByDateFromField'),
                                    (String) artifactAuthzRecord.get('filterByDateThruField'), nowTimestamp)
                        }
                        EntityList condList = efi.find('moqui.security.ArtifactAuthzRecordCond')
                                .condition('artifactAuthzId', aacv.get('artifactAuthzId')).useCache(true).list()
                        for (EntityValue cond in condList) {
                            String expCondValue = eci.resource.expand((String) cond.get('condValue'),
                                    "moqui.security.ArtifactAuthzRecordCond.${cond.artifactAuthzId}.${cond.artifactAuthzCondSeqId}")
                            if (expCondValue) {
                                ef.condition((String) cond.fieldName,
                                        efi.conditionFactory.comparisonOperatorFromEnumId((String) cond.operatorEnumId),
                                        expCondValue)
                            }
                        }

                        // anything found? if not it fails this condition, so skip the authz
                        if (ef.useCache(true).count() == 0) continue
                    }

                    String authzTypeEnumId = aacv.get('authzTypeEnumId')
                    if (aacv.get('authzServiceName')) {
                        Map result = eci.getService().sync().name(aacv.getString('authzServiceName'))
                                .parameters([userId:userId, authzActionEnumId:aeii.getActionEnumId(),
                                artifactTypeEnumId:aeii.getTypeEnumId(), artifactName:aeii.getName()]).call()
                        if (result?.authzTypeEnumId) authzTypeEnumId = result.authzTypeEnumId
                    }

                    // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO")) logger.warn("TOREMOVE found authz record for aeii [${aeii}]: ${aacv}")
                    if (authzTypeEnumId == 'AUTHZT_DENY') {
                        // we already know last was not always allow (checked above), so keep going in loop just in case we
                        // find an always allow in the query
                        denyAacv = aacv
                    } else if (authzTypeEnumId == 'AUTHZT_ALWAYS') {
                        aeii.copyAacvInfo(aacv, userId)
                        // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact isPermitted found always allow for user ${userId} - ${aeii}")
                        return true
                    } else if (authzTypeEnumId == 'AUTHZT_ALLOW' && denyAacv == null) {
                        // see if there are any denies in AEIs on lower on the stack
                        boolean ancestorDeny = false
                        for (ArtifactExecutionInfoImpl ancestorAeii in artifactExecutionInfoStack)
                            if (ancestorAeii.getAuthorizedAuthzTypeId() == 'AUTHZT_DENY') ancestorDeny = true

                        if (!ancestorDeny) {
                            aeii.copyAacvInfo(aacv, userId)
                            // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO")) logger.warn("TOREMOVE artifact isPermitted allow with no deny for user ${userId} - ${aeii}")
                            return true
                        }
                    }
                }
            }
        } finally {
            if (!alreadyDisabled) enableAuthz()
        }

        if (denyAacv != null) {
            // record that this was an explicit deny (for push or exception in case something catches and handles it)
            aeii.copyAacvInfo(denyAacv, userId)

            if (!requiresAuthz || this.authzDisabled) {
                // if no authz required, just return true even though it was a failure
                // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO")) logger.warn("TOREMOVE artifact isPermitted (in deny) doesn't require authz or authzDisabled for user ${userId} - ${aeii}")
                return true
            } else {
                StringBuilder warning = new StringBuilder()
                warning.append("User [${userId}] is not authorized for ${aeii.getTypeEnumId()} [${aeii.getName()}] because of a deny record [type:${aeii.getTypeEnumId()},action:${aeii.getActionEnumId()}], here is the current artifact stack:")
                for (def warnAei in this.stack) warning.append("\n").append(warnAei)
                logger.warn(warning.toString())

                alreadyDisabled = disableAuthz()
                try {
                    eci.getService().sync().name("create", "moqui.security.ArtifactAuthzFailure").parameters(
                            [artifactName:aeii.getName(), artifactTypeEnumId:aeii.getTypeEnumId(),
                            authzActionEnumId:aeii.getActionEnumId(), userId:userId,
                            failureDate:new Timestamp(System.currentTimeMillis()), isDeny:"Y"]).call()
                } finally {
                    if (!alreadyDisabled) enableAuthz()
                }

                return false
            }
        } else {
            // no perms found for this, only allow if the current AEI has inheritable auth and same user, and (ALL action or same action)

            // NOTE: this condition allows any user to be authenticated and allow inheritance if the last artifact was
            //       logged in anonymously (ie userId="_NA_"); consider alternate approaches; an alternate approach is
            //       in place when no user is logged in, but when one is this is the only solution so far
            if (lastAeii != null && lastAeii.authorizationInheritable &&
                    (lastAeii.authorizedUserId == "_NA_" || lastAeii.authorizedUserId == userId) &&
                    (lastAeii.authorizedActionEnumId == "AUTHZA_ALL" || lastAeii.authorizedActionEnumId == aeii.getActionEnumId()) &&
                    !"AUTHZT_DENY".equals(lastAeii.getAuthorizedAuthzTypeId())) {
                aeii.copyAuthorizedInfo(lastAeii)
                // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact isPermitted inheritable and same user and ALL or same action for user ${userId} - ${aeii}")
                return true
            }
        }

        if (!requiresAuthz || this.authzDisabled) {
            // if no authz required, just push it even though it was a failure
            if (lastAeii != null && lastAeii.authorizationInheritable) aeii.copyAuthorizedInfo(lastAeii)
            // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact isPermitted doesn't require authz or authzDisabled for user ${userId} - ${aeii}")
            return true
        } else {
            // if we got here no authz found, log it
            if (logger.isDebugEnabled()) {
                StringBuilder warning = new StringBuilder()
                warning.append("User [${userId}] is not authorized for ${aeii.getTypeEnumId()} [${aeii.getName()}] because of no allow record [type:${aeii.getTypeEnumId()},action:${aeii.getActionEnumId()}]\nlastAeii=[${lastAeii}]\nHere is the artifact stack:")
                for (def warnAei in this.stack) warning.append("\n").append(warnAei)
                logger.debug(warning.toString())
            }

            alreadyDisabled = disableAuthz()
            try {
                // NOTE: this is called sync because failures should be rare and not as performance sensitive, and
                //  because this is still in a disableAuthz block (if async a service would have to be written for that)
                eci.service.sync().name("create", "moqui.security.ArtifactAuthzFailure").parameters(
                        [artifactName:aeii.getName(), artifactTypeEnumId:aeii.getTypeEnumId(),
                        authzActionEnumId:aeii.getActionEnumId(), userId:userId,
                        failureDate:new Timestamp(System.currentTimeMillis()), isDeny:"N"]).call()
            } finally {
                if (!alreadyDisabled) enableAuthz()
            }

            return false
        }

        // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact isPermitted got to end for user ${userId} - ${aeii}")
        // return true
    }
}
