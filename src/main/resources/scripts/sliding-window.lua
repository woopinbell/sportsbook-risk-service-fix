-- Sliding-window counter for risk-service per-user / per-market limits.
--
-- Invariant: KEYS[2] (the sum key) always equals the sum of the encoded
-- amounts of the members currently in KEYS[1] (the sorted set), provided
-- every write goes through this script. Both writes (record an event) and
-- reads (peek current sum) flow through the same script so the expired-
-- entry cleanup is always atomic with the read or write.
--
-- KEYS[1] = sorted set key (eg. limit:user:{userId}:stake-daily)
-- KEYS[2] = sum key        (eg. limit:user:{userId}:stake-daily:sum)
--
-- ARGV[1] = now            (epoch milliseconds)
-- ARGV[2] = window-ms      (sliding window length in ms)
-- ARGV[3] = member         (empty string for read-only peek; otherwise the
--                           token "<betId>|<amount>" — the encoded amount
--                           is what cleanup credits back to the sum key)
-- ARGV[4] = amount         (0 for read-only peek; otherwise the long minor
--                           units to INCRBY against the sum key)
-- ARGV[5] = ttl-seconds    (EXPIRE refresh applied to both keys; pick at
--                           least 2 * window so a quiet user does not lose
--                           accurate state if they come back inside the
--                           window after Redis has aged the keys out)
--
-- Returns: the current windowed sum after cleanup and (if any) record.

local nowMs = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local member = ARGV[3]
local amount = tonumber(ARGV[4])
local ttlSeconds = tonumber(ARGV[5])

local cutoff = nowMs - windowMs

-- 1. Find expired members, sum the amounts they encoded, then DECRBY the
--    sum key and trim them out of the sorted set. Doing the per-member
--    accounting up front keeps the sum key in lockstep with the sorted
--    set even when many entries age out at once.
local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', '(' .. tostring(cutoff))
if #expired > 0 then
    local expiredSum = 0
    for i = 1, #expired do
        local m = expired[i]
        local pipe = string.find(m, '|', 1, true)
        if pipe ~= nil then
            local encoded = tonumber(string.sub(m, pipe + 1))
            if encoded ~= nil then
                expiredSum = expiredSum + encoded
            end
        end
    end
    if expiredSum > 0 then
        redis.call('DECRBY', KEYS[2], expiredSum)
    end
    redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. tostring(cutoff))
end

-- 2. Append the new entry if this call is a record (peek calls pass an
--    empty member and amount=0). The sorted set score is the event time
--    in ms, so future cleanups can use a single ZRANGEBYSCORE cut.
if amount > 0 and member ~= '' then
    redis.call('ZADD', KEYS[1], nowMs, member)
    redis.call('INCRBY', KEYS[2], amount)
end

-- 3. Refresh TTLs on both keys. EXPIRE on a missing key is a no-op so it
--    is safe to call regardless of whether step 2 created anything.
redis.call('EXPIRE', KEYS[1], ttlSeconds)
redis.call('EXPIRE', KEYS[2], ttlSeconds)

-- 4. Return the current sum, defaulting to 0 when the sum key was missing.
local current = redis.call('GET', KEYS[2])
if current == false then
    return 0
end
return tonumber(current)
