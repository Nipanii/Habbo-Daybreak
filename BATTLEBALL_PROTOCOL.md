# Original BattleBall protocol (version 1)

This is the local NGH protocol for the original Shockwave `hh_game_bb` game. It is not
Battle Banzai. Integers are signed 32-bit values, booleans use the normal Arcturus boolean
encoding, strings use the normal length-prefixed UTF-8 encoding, and doubles use the normal
packet double encoding. Every variable-length collection is count-prefixed. Incoming handlers
must reject invalid counts, identifiers, coordinates, enum values, and trailing payload.

## Shared limits and enums

- Protocol version: `1`
- Instance name: 1–32 characters after trimming
- Map: `1..5`
- Teams: `2..4`; team IDs are zero-based
- Players per team: `1..5`
- Round duration: `60..600` seconds
- Enabled powers: distinct IDs `1..8`, at most 8 entries. Power `9` is the question-mark
  pickup and resolves server-side to one enabled power.
- Instance state: `0=lobby`, `1=countdown`, `2=running`, `3=ending`, `4=disposed`
- Tile state: `0=default`, `1=touched`, `2=clicked`, `3=pressed`, `4=sealed`
- Tile colour: `-2=disabled`, `-1=default`, `0=red`, `1=blue`, `2=yellow`, `3=green`
- Player state: `0=normal`, `1=stunned`, `2=turbo`, `3=high-jumps`, `4=cleaning`,
  `5=colouring-for-opponent`, `6=entering-cannon`, `7=flying`, `8=ball-broken`
- Stable failure reasons: `0=unknown`, `1=not-found`, `2=not-owner`, `3=bad-state`,
  `4=full`, `5=bad-team`, `6=bad-parameters`, `7=insufficient-tickets`,
  `8=already-in-game`, `9=not-enough-teams`, `10=not-a-player`, `11=not-allowed`

## Client to server

| Header | Name | Payload |
|---:|---|---|
| 7500 | GetInstanceList | none |
| 7501 | Observe | `instanceId:int` |
| 7502 | Unobserve | `instanceId:int` |
| 7503 | InitiateCreate | none; server replies with current lounge/default parameters |
| 7504 | SetGameParameters | `name:string, mapId:int, roundSeconds:int, teamCount:int, teamCapacity:int, selectedTeam:int, private:boolean, powerCount:int, powerId:int...` |
| 7505 | Delete | `instanceId:int` |
| 7506 | InitiateJoin | `instanceId:int` |
| 7507 | SetJoinParameters | `instanceId:int, selectedTeam:int` |
| 7508 | Leave | `instanceId:int` |
| 7509 | Kick | `instanceId:int, targetUserId:int` |
| 7510 | Watch | `instanceId:int` |
| 7511 | Start | `instanceId:int` |
| 7512 | GameEvent | `instanceId:int, subtype:int`, then subtype `2`: `x:int,y:int`; subtype `4`: `powerId:int` |
| 7513 | Rejoin | `instanceId:int` |
| 7514 | RequestFullStatus | `instanceId:int, lastSequence:int` |

Movement coordinates and power activation are requests only. Arcturus remains authoritative for
walkability, movement, tile ownership, pickup ownership, effects, scoring, and match state.

## Server to client

All instance-bearing messages identify the `instanceId`. Snapshot and delta messages additionally
carry a monotonically increasing mutation `sequence`. One 500 ms delta may aggregate several
mutations and therefore advance by more than one. A delta also carries the preceding
`baseSequence`; the client applies it only when that value equals its current sequence. A mismatch
requests 7514/7612 recovery. Stale/duplicate sequences are ignored.

| Header | Name | Payload |
|---:|---|---|
| 7600 | LoungeInfo | `protocolVersion:int, ticketCurrencyType:int, ticketCost:int, playerSkillLevel:int` |
| 7601 | InstanceList | `count:int`, then instance summaries |
| 7602 | GameInstance | one instance summary |
| 7603 | InstanceNotAvailable | `instanceId:int, reason:int` |
| 7604 | GameParameters | `instanceId:int` plus the accepted creation parameters |
| 7605 | CreateFailed | `reason:int` |
| 7606 | GameDeleted | `instanceId:int` |
| 7607 | JoinParameters | `instanceId:int, teamCount:int`, then `teamId:int, playerCount:int, capacity:int` |
| 7608 | JoinFailed | `instanceId:int, reason:int` |
| 7609 | WatchFailed | `instanceId:int, reason:int` |
| 7610 | GameLocation | `instanceId:int, mapId:int, spectator:boolean` |
| 7611 | StartFailed | `instanceId:int, reason:int` |
| 7612 | FullGameStatus | full snapshot below |
| 7613 | GameStatus | delta below |
| 7614 | PlayerRejoined | `instanceId:int, userId:int` |
| 7615 | GameStart | `instanceId:int, sequence:int, countdownMs:int, roundMs:int` |
| 7616 | GameEnd | `instanceId:int, sequence:int, winningTeam:int, scoreCount:int`, then team scores |
| 7617 | GameReset | `instanceId:int, sequence:int` |
| 7618 | GamePlayerInfo | `instanceId:int` plus one player object |
| 7619 | IdleWarning | `instanceId:int, secondsRemaining:int` |
| 7620 | SkillLevelChanged | `skillLevel:int` |

An instance summary is:

`instanceId:int, name:string, ownerUserId:int, ownerName:string, mapId:int, state:int,
private:boolean, teamCount:int, playerCount:int, capacity:int, roundSeconds:int`

### Full snapshot (7612)

`protocolVersion:int, instanceId:int, baseSequence:int, sequence:int, state:int, timeRemainingMs:int,
stateDurationMs:int, objectCount:int, objects..., width:int, height:int, tileCount:int,
tiles..., scoreCount:int, (teamId:int,score:int)..., eventCount:int, events...`

Objects begin with `objectType:int`:

- `0` player: `objectId:int, userId:int, x:int, y:int, z:double, direction:int,
  playerState:int, colouringForOpponentObjectId:int, name:string, motto:string,
  figure:string, sex:string, teamId:int, roomIndex:int`
- `1` pickup: `objectId:int, ttlTicks:int, holderObjectId:int, powerType:int,
  x:int, y:int, z:double`
- `2` pin: `objectId:int, x:int, y:int, z:double, ttlTicks:int`

Each tile is `x:int, y:int, colour:int, state:int, floorHeight:int`. `tileCount` must equal
`width*height`; disabled map cells are transmitted so the client can build a deterministic grid.
`floorHeight` is the cell's level from the map heightmap, in original map units. Arenas are
multi-level, and the client raises both the tile and whatever stands on it by that many units, so
it must be present on every tile in both the snapshot and the delta.

### Delta (7613)

`protocolVersion:int, instanceId:int, sequence:int, state:int, timeRemainingMs:int,
objectCount:int, objects..., priorityTileCount:int, tiles..., fillTileCount:int, tiles...,
scoreCount:int, (teamId:int,score:int)..., eventCount:int, events...`

Priority tiles are direct landings/power effects and animate before flood-fill tiles. Object entries
are complete current objects rather than partial field patches. Events begin with `eventType:int`:

- `0` spawn: one complete object
- `1` despawn: `objectId:int`
- `2` movement accepted: `playerObjectId:int, goalX:int, goalY:int`
- `3` pickup: `playerObjectId:int, pickupObjectId:int, powerType:int`
- `4` player state: `playerObjectId:int, playerState:int, durationMs:int`
- `5` activation: `playerObjectId:int, pickupObjectId:int, effectDirection:int, powerType:int`

Events are presentation hints. The object/tile/score state in the same sequence is authoritative.

## Lifecycle and reliability

- Lobby mutations immediately broadcast 7602 or 7606 to observers.
- Creation is idempotent while the creator owns a lobby. Tickets are charged once per active
  player, atomically, after start validation and immediately before that round enters countdown.
- Disconnects reserve the player slot for a configurable grace period. 7513 restores that player;
  expiry removes it and broadcasts the new lobby or running state.
- A match owns one 500 ms scheduled task. Terminal transitions and disposal cancel it exactly once.
- Watchers receive the same snapshots/deltas but cannot send movement or power actions.
- A stale sequence, late join, watch, reset, or rejoin is repaired with 7612; normal play uses 7613.
