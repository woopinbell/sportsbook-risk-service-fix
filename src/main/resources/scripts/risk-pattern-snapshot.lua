-- Point-in-time read of the three pattern fact groups for one candidate bet.

local nowMs = tonumber(ARGV[1])
local rapidEnabled = ARGV[2] == '1'
local rapidWindowMs = tonumber(ARGV[3])
local suddenEnabled = ARGV[4] == '1'
local suddenLookback = tonumber(ARGV[5])
local repeatedEnabled = ARGV[6] == '1'
local repeatedWindowMs = tonumber(ARGV[7])

local function failure(reply)
    return {ok = false, error = reply.err}
end

local function success(value)
    return {ok = true, value = value}
end

local result = {selections = {}}
if rapidEnabled then
    local rapidReply = redis.pcall('ZCOUNT', KEYS[1], nowMs - rapidWindowMs, nowMs)
    if type(rapidReply) == 'table' and rapidReply.err ~= nil then
        result.rapid = failure(rapidReply)
    else
        result.rapid = success(tostring(rapidReply))
    end
else
    result.rapid = success('0')
end

if suddenEnabled then
    local stakesReply = redis.pcall('ZREVRANGE', KEYS[1], 0, suddenLookback - 1)
    if type(stakesReply) == 'table' and stakesReply.err ~= nil then
        result.stakes = failure(stakesReply)
    else
        local amounts = {}
        for i = #stakesReply, 1, -1 do
            local member = stakesReply[i]
            local pipe = string.find(member, '|', 1, true)
            if pipe ~= nil then
                table.insert(amounts, string.sub(member, pipe + 1))
            end
        end
        if #amounts == 0 then
            result.stakes = success('[]')
        else
            result.stakes = success(cjson.encode(amounts))
        end
    end
else
    result.stakes = success('[]')
end

for i = 2, #KEYS do
    if repeatedEnabled then
        local selectionReply = redis.pcall('ZCOUNT', KEYS[i], nowMs - repeatedWindowMs, nowMs)
        if type(selectionReply) == 'table' and selectionReply.err ~= nil then
            result.selections[tostring(i - 1)] = failure(selectionReply)
        else
            result.selections[tostring(i - 1)] = success(tostring(selectionReply))
        end
    else
        result.selections[tostring(i - 1)] = success('0')
    end
end

return cjson.encode(result)
