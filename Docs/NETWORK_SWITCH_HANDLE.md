# Network Switch Handling Documentation

## Problem Statement

Android's `NetworkCallback.onAvailable()` and `NetworkCallback.onLost()` fire in **non-deterministic order** during network transitions. This causes race conditions where:
1. State flags (like `hasNoNetwork`) get corrupted
2. Peer reconnection is skipped when it should happen
3. Reconnection is triggered when no actual network is available

### Key Discovery (2026-01-04)

**Callback Order Observation:**
- **WiFi → Cellular**: `onLost(WiFi)` fires first, then `onAvailable(Cellular)`
- **Cellular → WiFi**: `onAvailable(WiFi)` fires first, then `onLost(Cellular)`

This asymmetry means we CANNOT rely on either callback happening first.

### Bug Example from Logs

**Scenario**: Device moved away from WiFi, no cellular enabled, returned to WiFi after 14 minutes.

```
14:20:49 [I] Network lost: 132
14:20:49 [I] Switched to new network - forcing reconnection  ← FALSE POSITIVE
14:20:49 [I] Forcing peer reconnection due to network change...
14:34:12 [I] Network available: 202
```

**What went wrong:**
1. At 14:20:49, `onLost(132)` checked `activeNetwork` → returned non-null (race condition/stale state)
2. Code incorrectly set `hasNoNetwork = false`
3. Called `retryPeersNow()` with NO actual connectivity → peers failed, entered backoff
4. At 14:34:12, `onAvailable(202)` checked `hasNoNetwork` → was false (corrupted)
5. Skipped reconnection because flag was wrong
6. Peers stuck in backoff, never reconnected

---

## Network Transition Scenarios

### Scenario 1: WiFi → Cellular Switch

**Callback Order:**
```
1. onLost(WiFi)      ← WiFi is gone
2. onAvailable(Cellular)  ← Cellular ready
```

**Desired Behavior:**
- Trigger `forceReconnect` immediately after cellular becomes available
- Small delay (300-500ms) to let cellular fully stabilize
- Fast handoff for user experience

**Implementation:**
- `onLost(WiFi)`: Mark that WiFi is gone, don't check for alternative networks
- `onAvailable(Cellular)`: Detect transition from WiFi → Cellular, trigger immediate retry

---

### Scenario 2: Cellular → WiFi Switch

**Callback Order:**
```
1. onAvailable(WiFi)      ← WiFi ready
2. onLost(Cellular)       ← Cellular gone
```

**Desired Behavior:**
- **DO NOT** trigger reconnection on `onAvailable(WiFi)`
- Wait for `onLost(Cellular)` signal
- Then trigger reconnection (WiFi already stable by this point)

**Rationale:**
- WiFi is typically more stable than cellular
- By waiting for cellular to fully disconnect, we ensure clean transition
- Avoids triggering reconnect while both networks are active

**Implementation:**
- `onAvailable(WiFi)`: Detect we're transitioning from Cellular, log and wait
- `onLost(Cellular)`: Verify WiFi is active, then trigger retry

---

### Scenario 3: WiFi Lost → No Network → WiFi Back

**Timeline:**
```
T+0:    onLost(WiFi)           ← WiFi disconnected
T+0:    No alternative network  ← Cellular disabled/unavailable
T+14m:  onAvailable(WiFi)       ← WiFi reconnected
```

**Desired Behavior:**
- Mark `hasNoNetwork = true` when WiFi is lost and no alternative exists
- When WiFi comes back, immediately trigger reconnection
- This is the "coming back from network outage" scenario

**Implementation:**
- `onLost(WiFi)`: Check `activeNetwork`, if null set `hasNoNetwork = true`
- `onAvailable(WiFi)`: If `hasNoNetwork == true`, immediate retry

---

### Scenario 4: Flapping Network (Low Signal)

**Problem:**
- On weak WiFi signal, device may rapidly switch: WiFi ↔ Cellular ↔ WiFi ↔ Cellular
- Can happen multiple times per second
- Would trigger excessive reconnection attempts

**Desired Behavior:**
- Add 500ms debounce/cooldown timer
- Only trigger reconnection if network has been stable for 500ms
- Prevents reconnection spam and battery drain

**Implementation:**
- Track `lastNetworkRetryTime`
- Cancel pending retry jobs when new network event arrives
- Only execute retry if 500ms has passed since last retry OR if coming from no-network state

---

## Implementation Strategy

### State Variables

```kotlin
private enum class NetworkType { NONE, WIFI, CELLULAR, OTHER }

private var currentNetworkType = NetworkType.NONE
private var hasNoNetwork = true
private var networkRetryJob: Job? = null
private var lastNetworkRetryTime = 0L

private val NETWORK_STABILIZATION_DELAY_MS = 300L  // Wait for network to stabilize
private val FLAP_PROTECTION_COOLDOWN_MS = 500L     // Prevent flapping reconnects
```

### Core Logic

#### onAvailable(network)

```kotlin
1. If isInitialCallback → skip (fired immediately on registration)

2. Determine network type (WiFi, Cellular, Other)

3. Check previous state:
   
   a) If hasNoNetwork == true:
      → "Network restored from outage"
      → Immediate reconnection
      → Set hasNoNetwork = false
   
   b) If previousType == WIFI && newType == CELLULAR:
      → "WiFi → Cellular transition"
      → Schedule retry after 300ms (let cellular stabilize)
      → Cancel if another network event arrives
   
   c) If previousType == CELLULAR && newType == WIFI:
      → "Cellular → WiFi transition"
      → Log: "Waiting for Cellular lost event"
      → DO NOT retry here
      → Set hasNoNetwork = false
   
   d) Other transitions:
      → Log network change
      → Set hasNoNetwork = false

4. Update currentNetworkType
```

#### onLost(network)

```kotlin
1. Determine what network was lost (WiFi, Cellular, Other)

2. Check activeNetwork:
   
   a) If lostType == CELLULAR && activeType == WIFI:
      → "Cellular lost, WiFi active"
      → Trigger immediate reconnection
      → Set hasNoNetwork = false
   
   b) If lostType == WIFI && activeNetwork exists:
      → Check if alternative has NET_CAPABILITY_INTERNET
      → If yes: set hasNoNetwork = false (onAvailable will handle)
      → If no: set hasNoNetwork = true
   
   c) If activeNetwork == null:
      → "No alternative network"
      → Set hasNoNetwork = true
      → Cancel pending retries

3. Cancel any pending networkRetryJob
```

### Flapping Protection

```kotlin
private fun scheduleNetworkRetry(reason: String) {
    val now = System.currentTimeMillis()
    val timeSinceLastRetry = now - lastNetworkRetryTime
    
    // Always allow if coming from no network
    if (hasNoNetwork) {
        retryPeersNow()
        lastNetworkRetryTime = now
        return
    }
    
    // Enforce cooldown for network switches
    if (timeSinceLastRetry < FLAP_PROTECTION_COOLDOWN_MS) {
        logDebug("Skipping retry - too soon since last ($timeSinceLastRetry ms)")
        return
    }
    
    // Cancel any pending retry
    networkRetryJob?.cancel()
    
    // Schedule with stabilization delay
    networkRetryJob = serviceScope.launch {
        delay(NETWORK_STABILIZATION_DELAY_MS)
        if (_isRunning.value) {
            logInfo("$reason - triggering reconnection")
            retryPeersNow()
            lastNetworkRetryTime = System.currentTimeMillis()
        }
    }
}
```

---

## Critical Rules

1. **Never check `activeNetwork` in `onLost` to make decisions** - race conditions
2. **Use network type transitions to determine behavior** - order independent
3. **Always cancel pending retry jobs** when new network event arrives
4. **Debounce rapid network changes** with cooldown timer
5. **Prioritize "from outage" scenario** - immediate retry if `hasNoNetwork == true`
6. **Don't retry on Cellular→WiFi `onAvailable`** - wait for `onLost(Cellular)`
7. **Always reset `hasNoNetwork` correctly** to avoid state corruption

---

## Testing Scenarios

When testing network handling, verify:

1. ✅ WiFi → Cellular: Reconnects within 1 second
2. ✅ Cellular → WiFi: Reconnects after Cellular lost event
3. ✅ WiFi lost → no network → WiFi back: Reconnects immediately when back
4. ✅ Rapid WiFi/Cellular switching: Only reconnects after network stabilizes (500ms)
5. ✅ Peers successfully connect after each transition
6. ✅ No "false positive" reconnection attempts with no actual network
7. ✅ Logs clearly show which scenario triggered reconnection

---

## Log Messages to Add

```kotlin
// In onAvailable
logInfo("Network available: $network (type: $newNetworkType, previous: $previousType)")

// Scenario identification
logInfo("Scenario: WiFi → Cellular - immediate retry")
logInfo("Scenario: Cellular → WiFi - waiting for Cellular lost")
logInfo("Scenario: Network restored from outage")

// In onLost  
logInfo("Network lost: $network (type: $lostNetworkType, active: $activeType)")
logInfo("Scenario: Cellular lost, WiFi active - triggering retry")

// Debouncing
logDebug("Retry scheduled in ${NETWORK_STABILIZATION_DELAY_MS}ms")
logDebug("Retry blocked - cooldown active (${timeSinceLastRetry}ms since last)")
```

---

## Future Improvements

1. **Connectivity validation**: Ping a known endpoint before declaring network ready
2. **Fallback timer**: If peers don't connect within 60s, force retry regardless
3. **Metrics**: Track reconnection success rate per network type
4. **User notification**: Show toast when reconnection happens (debug builds)