-- Idempotently moves an active reservation into the four committed sliding counters.
-- KEYS[1] lifecycle hash, KEYS[2] global active count.
-- ARGV[1] now-ms, ARGV[2] retention-ms, ARGV[3..10] window-ms/ttl-seconds pairs.

local nowMs = tonumber(ARGV[1])
local retentionMs = tonumber(ARGV[2])

local function decrement(key, amount)
    local nextValue = tonumber(redis.call('GET', key) or '0') - amount
    if nextValue <= 0 then
        redis.call('DEL', key)
    else
        redis.call('SET', key, tostring(nextValue))
    end
end

local function deactivate(userId, member, stake, selectionCount)
    local activeKey = 'risk:reservations:user:' .. userId
    local removed = redis.call('ZREM', activeKey, member)
    if removed == 1 then
        decrement(activeKey .. ':stake-sum', stake)
        decrement(activeKey .. ':selection-sum', selectionCount)
        decrement(KEYS[2], 1)
    end
end

local state = redis.call('HGET', KEYS[1], 'state')
if state == false then
    return 'NOT_FOUND'
end
if state == 'COMMITTED' then
    return 'REPLAYED'
end
if state == 'REJECTED' or state == 'RELEASED' or state == 'EXPIRED' then
    return 'TOMBSTONED'
end
if state ~= 'RESERVED' then
    return 'NOT_FOUND'
end

local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt') or '0')
local userId = redis.call('HGET', KEYS[1], 'userId')
local betId = redis.call('HGET', KEYS[1], 'betId')
local stake = tonumber(redis.call('HGET', KEYS[1], 'stake'))
local selectionCount = tonumber(redis.call('HGET', KEYS[1], 'selectionCount'))
local member = redis.call('HGET', KEYS[1], 'member')

if expiresAt <= nowMs then
    deactivate(userId, member, stake, selectionCount)
    redis.call('HSET', KEYS[1], 'state', 'EXPIRED', 'expiredAt', tostring(nowMs))
    redis.call('HDEL', KEYS[1],
        'rejectionReason', 'rejectionCurrent', 'rejectionLimit',
        'rejectionRequested', 'rejectionCurrency', 'rejectionAction',
        'rejectedAt', 'releasedAt', 'committedAt')
    redis.call('PEXPIRE', KEYS[1], retentionMs)
    return 'EXPIRED'
end

local function record(suffix, amount, windowMs, ttlSeconds)
    local zsetKey = 'limit:user:' .. userId .. ':' .. suffix
    local sumKey = zsetKey .. ':sum'
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
        elseif expiredSum > 0 then
            redis.call('DECRBY', sumKey, expiredSum)
        end
    end
    local inserted = redis.call('ZADD', zsetKey, 'NX', nowMs, betId .. '|' .. tostring(amount))
    if inserted == 1 then
        redis.call('INCRBY', sumKey, amount)
    end
    redis.call('EXPIRE', zsetKey, ttlSeconds)
    redis.call('EXPIRE', sumKey, ttlSeconds)
end

record('stake-daily', stake, tonumber(ARGV[3]), tonumber(ARGV[4]))
record('stake-weekly', stake, tonumber(ARGV[5]), tonumber(ARGV[6]))
record('stake-monthly', stake, tonumber(ARGV[7]), tonumber(ARGV[8]))
if selectionCount > 0 then
    record('selections-per-minute', selectionCount, tonumber(ARGV[9]), tonumber(ARGV[10]))
end

deactivate(userId, member, stake, selectionCount)
redis.call('HSET', KEYS[1], 'state', 'COMMITTED', 'committedAt', tostring(nowMs))
redis.call('HDEL', KEYS[1], 'releasedAt', 'expiredAt', 'rejectedAt')
redis.call('PEXPIRE', KEYS[1], retentionMs)
return 'APPLIED'
