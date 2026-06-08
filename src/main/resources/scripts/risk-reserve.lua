-- Atomically admits one candidate against committed and active-reservation totals.
-- The reservation hash is a lifecycle record retained for idempotency after terminal
-- REJECTED, RELEASED, EXPIRED, and COMMITTED transitions. This service intentionally
-- targets standalone Redis because lifecycle cleanup derives keys dynamically.
--
-- KEYS:
--  1 reservation hash, 2 active user zset, 3 reserved stake sum,
--  4 reserved selection sum, 5..12 committed counter zset/sum pairs,
-- 13 user override hash, 14 global active-reservation count.
--
-- ARGV:
--  1 now-ms, 2 lease-ms, 3 retention-ms, 4 fingerprint, 5 user-id, 6 bet-id,
--  7 stake, 8 selection-count, 9 currency,
-- 10 single, 11 daily, 12 weekly, 13 monthly, 14 selections defaults,
-- 15..22 committed counter window-ms/ttl-seconds pairs,
-- 23 pattern-block flag, 24 reason, 25 current, 26 limit, 27 requested,
-- 28 currency, 29 action, 30 JSON array of all pattern verdicts.

local nowMs = tonumber(ARGV[1])
local leaseMs = tonumber(ARGV[2])
local retentionMs = tonumber(ARGV[3])
local fingerprint = ARGV[4]
local userId = ARGV[5]
local betId = ARGV[6]
local stake = tonumber(ARGV[7])
local selectionCount = tonumber(ARGV[8])
local currency = ARGV[9]
local emptyPatterns = '[]'
local expiredCount = 0

local function decrement(key, amount)
    local current = tonumber(redis.call('GET', key) or '0')
    local nextValue = current - amount
    if nextValue <= 0 then
        redis.call('DEL', key)
    else
        redis.call('SET', key, tostring(nextValue))
    end
end

local function decrementActive(amount)
    if amount > 0 then
        decrement(KEYS[14], amount)
    end
end

local function removeActiveMember(reservationKey)
    local oldUser = redis.call('HGET', reservationKey, 'userId')
    local member = redis.call('HGET', reservationKey, 'member')
    if oldUser == false or member == false then
        return 0
    end
    local oldStake, oldSelections = string.match(member, '^(%d+)|(%d+)|')
    if oldStake == nil then
        return 0
    end
    local activeKey = 'risk:reservations:user:' .. oldUser
    local removed = redis.call('ZREM', activeKey, member)
    if removed == 1 then
        decrement(activeKey .. ':stake-sum', tonumber(oldStake))
        decrement(activeKey .. ':selection-sum', tonumber(oldSelections))
        decrementActive(1)
    end
    return removed
end

local function markExpired(reservationKey)
    if redis.call('HGET', reservationKey, 'state') ~= 'RESERVED' then
        return false
    end
    local expiresAt = tonumber(redis.call('HGET', reservationKey, 'expiresAt') or '0')
    if expiresAt > nowMs then
        return false
    end
    removeActiveMember(reservationKey)
    redis.call('HSET', reservationKey, 'state', 'EXPIRED', 'expiredAt', tostring(nowMs))
    redis.call('HDEL', reservationKey,
        'rejectionReason', 'rejectionCurrent', 'rejectionLimit',
        'rejectionRequested', 'rejectionCurrency', 'rejectionAction',
        'rejectedAt', 'releasedAt', 'committedAt')
    redis.call('PEXPIRE', reservationKey, retentionMs)
    expiredCount = expiredCount + 1
    return true
end

local function cleanupActiveUser()
    local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', nowMs)
    for i = 1, #expired do
        local reservedStake, reservedSelections, expiredBetId =
            string.match(expired[i], '^(%d+)|(%d+)|(.+)$')
        if reservedStake ~= nil then
            local expiredKey = 'risk:reservation:' .. expiredBetId
            if not markExpired(expiredKey) then
                local removed = redis.call('ZREM', KEYS[2], expired[i])
                if removed == 1 then
                    decrement(KEYS[3], tonumber(reservedStake))
                    decrement(KEYS[4], tonumber(reservedSelections))
                    decrementActive(1)
                end
            end
        else
            redis.call('ZREM', KEYS[2], expired[i])
        end
    end
end

local function readCommitted(zsetKey, sumKey, windowMs, ttlSeconds)
    local current = redis.call('GET', sumKey)
    if current == false and redis.call('EXISTS', zsetKey) == 0 then
        return 0
    end
    local cutoff = nowMs - windowMs
    local expired = redis.call('ZRANGEBYSCORE', zsetKey, '-inf', '(' .. tostring(cutoff))
    if #expired > 0 then
        local expiredSum = 0
        for i = 1, #expired do
            local encoded = string.match(expired[i], '|(%d+)$')
            if encoded ~= nil then
                expiredSum = expiredSum + tonumber(encoded)
            end
        end
        redis.call('ZREMRANGEBYSCORE', zsetKey, '-inf', '(' .. tostring(cutoff))
        if redis.call('ZCARD', zsetKey) == 0 then
            redis.call('DEL', sumKey)
            current = false
        elseif expiredSum > 0 then
            current = redis.call('DECRBY', sumKey, expiredSum)
        end
    elseif current ~= false and redis.call('ZCARD', zsetKey) == 0 then
        redis.call('DEL', sumKey)
        current = false
    end
    redis.call('EXPIRE', zsetKey, ttlSeconds)
    redis.call('EXPIRE', sumKey, ttlSeconds)
    return tonumber(current or '0') or 0
end

local function effectiveLimit(field, fallback)
    local value = redis.call('HGET', KEYS[13], field)
    if value == false then
        return tonumber(fallback)
    end
    return tonumber(value)
end

local function rejectionPayload(
    reason, current, limit, requested, rejectionCurrency, rejectionAction, replayed, patternsJson)
    return cjson.encode({
        status = 'REJECTED',
        reason = reason,
        current = current,
        limit = limit,
        requested = requested,
        currency = rejectionCurrency == nil and cjson.null or rejectionCurrency,
        action = rejectionAction,
        replayed = replayed,
        patternsJson = patternsJson,
        expired = expiredCount
    })
end

local function writeIdentity()
    redis.call('HSET', KEYS[1],
        'fingerprint', fingerprint,
        'userId', userId,
        'betId', betId,
        'stake', tostring(stake),
        'selectionCount', tostring(selectionCount),
        'currency', currency)
end

local function persistRejection(
    reason, current, limit, requested, rejectionCurrency, rejectionAction, patternsJson)
    writeIdentity()
    redis.call('HSET', KEYS[1],
        'state', 'REJECTED',
        'rejectionReason', reason,
        'rejectionCurrent', tostring(current),
        'rejectionLimit', tostring(limit),
        'rejectionRequested', tostring(requested),
        'rejectionCurrency', rejectionCurrency == nil and '' or rejectionCurrency,
        'rejectionAction', rejectionAction,
        'patternsJson', patternsJson,
        'rejectedAt', tostring(nowMs))
    redis.call('HDEL', KEYS[1],
        'member', 'reservedAt', 'expiresAt', 'releasedAt', 'expiredAt', 'committedAt')
    redis.call('PEXPIRE', KEYS[1], retentionMs)
    return rejectionPayload(
        reason, current, limit, requested, rejectionCurrency, rejectionAction, false, patternsJson)
end

local function replayRejection()
    local storedCurrency = redis.call('HGET', KEYS[1], 'rejectionCurrency')
    if storedCurrency == false or storedCurrency == '' then
        storedCurrency = nil
    end
    return rejectionPayload(
        redis.call('HGET', KEYS[1], 'rejectionReason'),
        tonumber(redis.call('HGET', KEYS[1], 'rejectionCurrent') or '0'),
        tonumber(redis.call('HGET', KEYS[1], 'rejectionLimit') or '0'),
        tonumber(redis.call('HGET', KEYS[1], 'rejectionRequested') or '0'),
        storedCurrency,
        redis.call('HGET', KEYS[1], 'rejectionAction') or 'BLOCK',
        true,
        redis.call('HGET', KEYS[1], 'patternsJson') or emptyPatterns)
end

cleanupActiveUser()
markExpired(KEYS[1])

local existingState = redis.call('HGET', KEYS[1], 'state')
if existingState ~= false then
    local existingFingerprint = redis.call('HGET', KEYS[1], 'fingerprint')
    if existingFingerprint ~= fingerprint then
        return cjson.encode({status = 'CONFLICT', expired = expiredCount})
    end
    if existingState == 'RESERVED' or existingState == 'COMMITTED' then
        local expiresAt = redis.call('HGET', KEYS[1], 'expiresAt')
        return cjson.encode({
            status = 'APPROVED',
            state = existingState,
            expiresAt = expiresAt == false and cjson.null or tonumber(expiresAt),
            replayed = true,
            patternsJson = redis.call('HGET', KEYS[1], 'patternsJson') or emptyPatterns,
            expired = expiredCount
        })
    end
    if existingState == 'REJECTED' then
        return replayRejection()
    end
    if existingState == 'RELEASED' then
        return rejectionPayload(
            'RISK_RESERVATION_RELEASED',
            0,
            0,
            0,
            nil,
            'BLOCK',
            true,
            redis.call('HGET', KEYS[1], 'patternsJson') or emptyPatterns)
    end
    if existingState ~= 'EXPIRED' then
        return cjson.encode({status = 'CONFLICT', expired = expiredCount})
    end
    -- EXPIRED with the same fingerprint is the one terminal state that may be
    -- re-reserved so betting reconciliation can recover after a lost lease.
end

local singleLimit = effectiveLimit('SINGLE_BET_MAX:' .. currency, ARGV[10])
if stake > singleLimit then
    return persistRejection(
        'SINGLE_BET_MAX_EXCEEDED', 0, singleLimit, stake, currency, 'BLOCK', emptyPatterns)
end

local reservedStake = tonumber(redis.call('GET', KEYS[3]) or '0')
local reservedSelections = tonumber(redis.call('GET', KEYS[4]) or '0')
local names = {'STAKE_DAILY', 'STAKE_WEEKLY', 'STAKE_MONTHLY'}
for i = 1, 3 do
    local keyIndex = 5 + ((i - 1) * 2)
    local argIndex = 15 + ((i - 1) * 2)
    local current = readCommitted(
        KEYS[keyIndex], KEYS[keyIndex + 1], tonumber(ARGV[argIndex]), tonumber(ARGV[argIndex + 1]))
    local limit = effectiveLimit(names[i] .. ':' .. currency, ARGV[10 + i])
    if current + reservedStake + stake > limit then
        return persistRejection(
            names[i] .. '_LIMIT_EXCEEDED',
            current + reservedStake,
            limit,
            stake,
            currency,
            'BLOCK',
            emptyPatterns)
    end
end

local selectionCurrent = readCommitted(
    KEYS[11], KEYS[12], tonumber(ARGV[21]), tonumber(ARGV[22]))
local selectionLimit = effectiveLimit('SELECTIONS_PER_MINUTE:KRW', ARGV[14])
if selectionCurrent + reservedSelections + selectionCount > selectionLimit then
    return persistRejection(
        'SELECTIONS_PER_MINUTE_LIMIT_EXCEEDED',
        selectionCurrent + reservedSelections,
        selectionLimit,
        selectionCount,
        nil,
        'BLOCK',
        emptyPatterns)
end

if ARGV[23] == '1' then
    local patternCurrency = ARGV[28]
    if patternCurrency == '' then
        patternCurrency = nil
    end
    return persistRejection(
        ARGV[24],
        tonumber(ARGV[25]),
        tonumber(ARGV[26]),
        tonumber(ARGV[27]),
        patternCurrency,
        ARGV[29],
        ARGV[30])
end

local expiresAt = nowMs + leaseMs
local member = tostring(stake) .. '|' .. tostring(selectionCount) .. '|' .. betId
writeIdentity()
redis.call('HSET', KEYS[1],
    'state', 'RESERVED',
    'reservedAt', tostring(nowMs),
    'expiresAt', tostring(expiresAt),
    'member', member,
    'patternsJson', ARGV[30])
redis.call('HDEL', KEYS[1],
    'rejectionReason', 'rejectionCurrent', 'rejectionLimit',
    'rejectionRequested', 'rejectionCurrency', 'rejectionAction',
    'rejectedAt', 'releasedAt', 'expiredAt', 'committedAt')
redis.call('PEXPIRE', KEYS[1], retentionMs)
redis.call('ZADD', KEYS[2], expiresAt, member)
redis.call('INCRBY', KEYS[3], stake)
redis.call('INCRBY', KEYS[4], selectionCount)
redis.call('INCRBY', KEYS[14], 1)
redis.call('PERSIST', KEYS[2])
redis.call('PERSIST', KEYS[3])
redis.call('PERSIST', KEYS[4])

return cjson.encode({
    status = 'APPROVED',
    state = 'RESERVED',
    expiresAt = expiresAt,
    replayed = false,
    patternsJson = ARGV[30],
    expired = expiredCount
})
