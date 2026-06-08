-- Idempotently terminates an active reservation as RELEASED or REJECTED.
-- KEYS[1] lifecycle hash, KEYS[2] global active count.
-- ARGV:
--  1 now-ms, 2 retention-ms, 3 target state (RELEASED or REJECTED),
--  4 reason, 5 current, 6 limit, 7 requested, 8 currency, 9 action.

local nowMs = tonumber(ARGV[1])
local retentionMs = tonumber(ARGV[2])
local targetState = ARGV[3]

local function decrement(key, amount)
    local nextValue = tonumber(redis.call('GET', key) or '0') - amount
    if nextValue <= 0 then
        redis.call('DEL', key)
    else
        redis.call('SET', key, tostring(nextValue))
    end
end

local function deactivate()
    local userId = redis.call('HGET', KEYS[1], 'userId')
    local stake = tonumber(redis.call('HGET', KEYS[1], 'stake'))
    local selectionCount = tonumber(redis.call('HGET', KEYS[1], 'selectionCount'))
    local member = redis.call('HGET', KEYS[1], 'member')
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
    return 'COMMITTED_CONFLICT'
end
if targetState == 'REJECTED' and state == 'REJECTED' then
    return 'REPLAYED'
end
if targetState == 'RELEASED' and state == 'RELEASED' then
    return 'REPLAYED'
end
if targetState == 'RELEASED' and state == 'EXPIRED' then
    redis.call('HSET', KEYS[1], 'state', 'RELEASED', 'releasedAt', tostring(nowMs))
    redis.call('HDEL', KEYS[1], 'expiredAt')
    redis.call('PEXPIRE', KEYS[1], retentionMs)
    return 'APPLIED'
end
if state == 'REJECTED' or state == 'RELEASED' or state == 'EXPIRED' then
    return 'TOMBSTONED'
end
if state ~= 'RESERVED' then
    return 'NOT_FOUND'
end

if targetState == 'RELEASED' then
    deactivate()
    redis.call('HSET', KEYS[1], 'state', 'RELEASED', 'releasedAt', tostring(nowMs))
    redis.call('HDEL', KEYS[1],
        'rejectionReason', 'rejectionCurrent', 'rejectionLimit',
        'rejectionRequested', 'rejectionCurrency', 'rejectionAction',
        'rejectedAt', 'expiredAt', 'committedAt')
    redis.call('PEXPIRE', KEYS[1], retentionMs)
    return 'APPLIED'
end

if targetState == 'REJECTED' then
    deactivate()
    redis.call('HSET', KEYS[1],
        'state', 'REJECTED',
        'rejectionReason', ARGV[4],
        'rejectionCurrent', ARGV[5],
        'rejectionLimit', ARGV[6],
        'rejectionRequested', ARGV[7],
        'rejectionCurrency', ARGV[8],
        'rejectionAction', ARGV[9],
        'rejectedAt', tostring(nowMs))
    redis.call('HDEL', KEYS[1], 'member', 'reservedAt', 'expiresAt',
        'releasedAt', 'expiredAt', 'committedAt')
    redis.call('PEXPIRE', KEYS[1], retentionMs)
    return 'APPLIED'
end

return 'NOT_FOUND'
