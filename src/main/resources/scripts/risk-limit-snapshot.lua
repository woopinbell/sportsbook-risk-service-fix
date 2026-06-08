-- Point-in-time read of all per-user sliding limits and overrides.
-- V1 intentionally targets standalone Redis; every key is evaluated by one server-side script.

local nowMs = tonumber(ARGV[1])
local retentionMs = tonumber(ARGV[14])
local result = {counters = {}, overrides = {}, expired = 0}

local function failure(reply)
    return {ok = false, error = reply.err}
end

local function success(value)
    return {ok = true, value = value}
end

local function readCounter(zsetKey, sumKey, windowMs, ttlSeconds)
    local currentReply = redis.pcall('GET', sumKey)
    if type(currentReply) == 'table' and currentReply.err ~= nil then
        return failure(currentReply)
    end
    local current = currentReply

    if current == false and redis.call('EXISTS', zsetKey) == 0 then
        return success('0')
    end

    local cutoff = nowMs - windowMs
    local expiredReply = redis.pcall('ZRANGEBYSCORE', zsetKey, '-inf', '(' .. tostring(cutoff))
    if type(expiredReply) == 'table' and expiredReply.err ~= nil then
        return failure(expiredReply)
    end
    local expired = expiredReply
    if #expired > 0 then
        local expiredSum = 0
        for i = 1, #expired do
            local member = expired[i]
            local pipe = string.find(member, '|', 1, true)
            if pipe ~= nil then
                local encoded = tonumber(string.sub(member, pipe + 1))
                if encoded ~= nil then
                    expiredSum = expiredSum + encoded
                end
            end
        end

        local removeReply = redis.pcall('ZREMRANGEBYSCORE', zsetKey, '-inf', '(' .. tostring(cutoff))
        if type(removeReply) == 'table' and removeReply.err ~= nil then
            return failure(removeReply)
        end
        local cardReply = redis.pcall('ZCARD', zsetKey)
        if type(cardReply) == 'table' and cardReply.err ~= nil then
            return failure(cardReply)
        end
        if cardReply == 0 then
            redis.call('DEL', sumKey)
            current = false
        elseif expiredSum > 0 then
            local decrementReply = redis.pcall('DECRBY', sumKey, expiredSum)
            if type(decrementReply) == 'table' and decrementReply.err ~= nil then
                return failure(decrementReply)
            end
            current = decrementReply
        end
    elseif current ~= false then
        local cardReply = redis.pcall('ZCARD', zsetKey)
        if type(cardReply) == 'table' and cardReply.err ~= nil then
            return failure(cardReply)
        end
        if cardReply == 0 then
            redis.call('DEL', sumKey)
            current = false
        end
    end

    redis.call('EXPIRE', zsetKey, ttlSeconds)
    redis.call('EXPIRE', sumKey, ttlSeconds)
    if current == false or tonumber(current) == nil then
        return success('0')
    end
    return success(tostring(current))
end

local function decrement(key, amount)
    local nextValue = tonumber(redis.call('GET', key) or '0') - amount
    if nextValue <= 0 then
        redis.call('DEL', key)
    else
        redis.call('SET', key, tostring(nextValue))
    end
end

local function reservedTotals()
    local expired = redis.call('ZRANGEBYSCORE', KEYS[10], '-inf', nowMs)
    for i = 1, #expired do
        local stake, selections, betId = string.match(expired[i], '^(%d+)|(%d+)|(.+)$')
        if stake ~= nil then
            local reservationKey = 'risk:reservation:' .. betId
            local state = redis.call('HGET', reservationKey, 'state')
            local expiresAt = tonumber(redis.call('HGET', reservationKey, 'expiresAt') or '0')
            if state ~= 'RESERVED' or expiresAt <= nowMs then
                local removed = redis.call('ZREM', KEYS[10], expired[i])
                if removed == 1 then
                    decrement(KEYS[11], tonumber(stake))
                    decrement(KEYS[12], tonumber(selections))
                    decrement(KEYS[13], 1)
                end
                if state == 'RESERVED' then
                    redis.call('HSET', reservationKey,
                        'state', 'EXPIRED', 'expiredAt', tostring(nowMs))
                    redis.call('HDEL', reservationKey,
                        'rejectionReason', 'rejectionCurrent', 'rejectionLimit',
                        'rejectionRequested', 'rejectionCurrency', 'rejectionAction',
                        'rejectedAt', 'releasedAt', 'committedAt')
                    redis.call('PEXPIRE', reservationKey, retentionMs)
                    result.expired = result.expired + 1
                end
            end
        else
            redis.call('ZREM', KEYS[10], expired[i])
        end
    end
    return tonumber(redis.call('GET', KEYS[11]) or '0'),
        tonumber(redis.call('GET', KEYS[12]) or '0')
end

local names = {'STAKE_DAILY', 'STAKE_WEEKLY', 'STAKE_MONTHLY', 'SELECTIONS_PER_MINUTE'}
local reservedStake, reservedSelections = reservedTotals()
for i = 1, #names do
    local keyIndex = (i - 1) * 2 + 1
    local argIndex = (i - 1) * 2 + 2
    local captured =
        readCounter(KEYS[keyIndex], KEYS[keyIndex + 1], tonumber(ARGV[argIndex]), tonumber(ARGV[argIndex + 1]))
    if captured.ok then
        local reserved = i == 4 and reservedSelections or reservedStake
        captured.value = tostring((tonumber(captured.value) or 0) + reserved)
    end
    result.counters[names[i]] = captured
end

for i = 1, #names do
    local overrideReply = redis.pcall('HGET', KEYS[9], ARGV[9 + i])
    if type(overrideReply) == 'table' and overrideReply.err ~= nil then
        result.overrides[names[i]] = failure(overrideReply)
    elseif overrideReply == false then
        result.overrides[names[i]] = success(nil)
    else
        result.overrides[names[i]] = success(overrideReply)
    end
end

return cjson.encode(result)
