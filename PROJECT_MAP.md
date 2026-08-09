# Project Map

Map of core source files for the Penik Messenger project. Paths are relative to the project root; cache, logs, vendor code, and auto-generated build artifacts are excluded.

## Network

### Go server

- `server/cmd/server/main.go` — Server entry point: loads config, opens DB, registers REST/WebSocket routes, attaches middleware, and serves the embedded web client.
- `server/internal/config/config.go` — Loads runtime configuration from environment variables: port, SQLite path, session TTL, size limits, CORS, and upload directory.
- `server/internal/handlers/auth.go` — REST handlers for registration and login; validates credentials and key material, hashes passwords, and creates devices/sessions.
- `server/internal/handlers/users.go` — Handlers for user profiles, searching users, changing name/nickname, and avatar operations.
- `server/internal/handlers/messages.go` — REST access to direct message history, delivery/read receipts, and chat deletion operations.
- `server/internal/handlers/groups.go` — REST lifecycle of groups: creation, retrieval, renaming, deletion, and membership management.
- `server/internal/handlers/group_keys.go` — REST operations for group key versions and encrypted key envelopes for devices.
- `server/internal/handlers/group_history.go` — Handles upload and one-time distribution of encrypted group history to new devices.
- `server/internal/handlers/pairing.go` — Creates, presents, and manages pairing sessions for linking new devices and transferring history.
- `server/internal/handlers/keys.go` — REST handlers for publishing identity/key material, retrieving key bundles, and key backups.
- `server/internal/handlers/presence.go` — Serves and broadcasts user presence states and active device information.
- `server/internal/handlers/vkupload.go` — Accepts encrypted attachment payloads and uploads them to VK CDN via VK Docs API; proxies downloads through a VK host allowlist, resolving document preview pages to their direct link.
- `server/internal/handlers/ws.go` — Authorizes WebSocket upgrades and creates server-side client sessions for real-time event exchange.
- `server/internal/ws/client.go` — Implements the WebSocket client read/write pump, handling direct messages, key requests, receipt events, offline batching, and presence.
- `server/internal/ws/group.go` — Receives and routes encrypted group messages, receipts, and offline delivery across group members.
- `server/internal/ws/hub.go` — Manages the registry of connected devices, broadcasting pre-encoded frames, presence, and shutdown events.
- `server/internal/ws/protocol.go` — Defines binary opcodes and MsgPack structures for direct/group messages, keys, pairing, presence, and statuses.
- `server/internal/middleware/auth.go` — Extracts bearer/WebSocket tokens, validates sessions in the DB, and injects user/device IDs into the request context.
- `server/internal/middleware/cors.go` — Configures CORS, origin checks, and CSRF protection for HTTP requests.
- `server/internal/middleware/rate_limit.go` — Provides IP- and user-based rate limiting for public and sensitive operations.
- `server/internal/middleware/limit.go` — Limits the maximum HTTP request body size.

### Browser client transport

- `client/js/api.js` — Unified browser REST client: attaches tokens, serializes JSON, parses errors, and exports APIs for users, messages, pairing, and groups.
- `client/js/pairing.js` — Decrypts and imports history transferred from Android into the browser IndexedDB stores.
- `client/js/ws.js` — Manages the browser WebSocket connection: encodes/decodes MsgPack frames, supports opcodes, ping/pong, request queuing, and exponential backoff reconnection.
- `client/js/presence.js` — Publishes user presence events and provides handlers for online status updates.

### Android client transport

- `android/app/src/main/java/niel/kro/penik/data/network/api/ApiService.kt` — Retrofit contract for the Android client's REST API covering auth, profiles, messages, pairing, groups, keys, and avatars.
- `android/app/src/main/java/niel/kro/penik/data/network/api/ApiModels.kt` — Kotlin data models for Retrofit API requests and responses.
- `android/app/src/main/java/niel/kro/penik/data/network/websocket/WebSocketManager.kt` — Maintains the OkHttp WebSocket connection, binary MsgPack protocol, reconnects, ping/pong, and flow of typed events.
- `android/app/src/main/java/niel/kro/penik/data/repository/SecureTokenStorage.kt` — Stores tokens, user/device IDs, and cryptographic keys in secure local storage.

## UI

### Browser client

- `index.html` — Root HTML template for the web client and application container.
- `client/index.html` — Source HTML entry point for the Vite client.
- `client/css/main.css` — Core browser UI styles: layout, navigation, chats, groups, forms, responsive behavior, and the `.msg-time-tooltip` hover popup.
- `client/js/app.js` — Main application controller: user state, hash navigation, screen layout, storage initialization, and REST/WebSocket/crypto coordination.
- `client/js/ui/auth.js` — Renders login/registration screens and binds forms to the auth API.
- `client/js/ui/chat.js` — Displays the chat list, direct messaging room, messages, delivery/read receipts, and input controls.
- `client/js/ui/groups.js` — Displays the group list and group chat room, including messages, invitations, and member actions.
- `client/js/ui/profile.js` — Profile screen for editing user data and uploading avatars.
- `client/js/ui/search.js` — User search screen and initiator for direct chats.
- `client/js/ui/components.js` — Shared UI components: avatars, time formatting, hover tooltip for full timestamp, message copy menu, scroll-down button, toasts, and modals.
- `client/js/globals.d.ts` — Ambient type declarations for globals the app attaches to `window`; type-checking only, emits no JavaScript.

### Android client

- `android/app/src/main/AndroidManifest.xml` — Android app declaration, components, permissions, application class, and FileProvider for decrypted attachments.
- `android/app/src/main/res/xml/attachment_paths.xml` — FileProvider path configuration granting read-only content URIs for decrypted files in `cacheDir/attachments`.
- `android/app/src/main/java/niel/kro/penik/MainActivity.kt` — Main Activity; enables edge-to-edge mode and launches Compose navigation within the app theme.
- `android/app/src/main/java/niel/kro/penik/PenikApplication.kt` — Hilt Application class and entry point for the global WebSocket event coordinator.
- `android/app/src/main/java/niel/kro/penik/ui/navigation/NavGraph.kt` — Compose Navigation graph for auth, main screen, direct/group chats, group settings, and pairing scanner.
- `android/app/src/main/java/niel/kro/penik/ui/navigation/NavRoutes.kt` — Declares typed routes and screen parameters for the Android client.
- `android/app/src/main/java/niel/kro/penik/ui/navigation/MainScreen.kt` — Main app layout combining chat lists, groups, profile, and logout/pairing actions.
- `android/app/src/main/java/niel/kro/penik/ui/screen/auth/AuthScreen.kt` — User login and registration UI.
- `android/app/src/main/java/niel/kro/penik/ui/screen/chatslist/ChatsListScreen.kt` — Direct chat list UI and navigation to chat rooms.
- `android/app/src/main/java/niel/kro/penik/ui/screen/chatroom/ChatRoomScreen.kt` — Direct chat room UI: message history, input, sending, receipts, connection state; scroll-down FAB shown only when last message is not visible.
- `android/app/src/main/java/niel/kro/penik/ui/screen/groups/GroupsListScreen.kt` — Group list UI and pending invitations.
- `android/app/src/main/java/niel/kro/penik/ui/screen/groups/GroupChatScreen.kt` — Group chat room UI with messages and group actions; scroll-down FAB shown only when last message is not visible.
- `android/app/src/main/java/niel/kro/penik/ui/screen/groups/GroupSettingsScreen.kt` — Group settings screen: member list, roles, invitations, and key rotation.
- `android/app/src/main/java/niel/kro/penik/ui/screen/profile/ProfileScreen.kt` — Profile UI, name/password changes, avatar management, and key backup.
- `android/app/src/main/java/niel/kro/penik/ui/screen/pairing/PairingScannerScreen.kt` — Screen for scanning and processing QR pairing sessions for new devices.
- `android/app/src/main/java/niel/kro/penik/ui/viewmodel/AuthViewModel.kt` — Manages login/registration state and actions.
- `android/app/src/main/java/niel/kro/penik/ui/viewmodel/ChatRoomViewModel.kt` — Subscribes the chat room to messages, handles sending, and processes statuses.
- `android/app/src/main/java/niel/kro/penik/ui/viewmodel/ChatsListViewModel.kt` — Loads and observes the direct chat list.
- `android/app/src/main/java/niel/kro/penik/ui/viewmodel/GroupsViewModel.kt` — Manages the group list, synchronization, and group-level actions.
- `android/app/src/main/java/niel/kro/penik/ui/viewmodel/GroupSettingsViewModel.kt` — Manages changes to group composition, roles, names, and keys.
- `android/app/src/main/java/niel/kro/penik/ui/viewmodel/ProfileViewModel.kt` — Manages profile data, avatars, passwords, and key backups.
- `android/app/src/main/java/niel/kro/penik/ui/viewmodel/StartupViewModel.kt` — Determines the initial navigation route based on authorization state.
- `android/app/src/main/java/niel/kro/penik/ui/components/Components.kt` — Reusable Compose UI components, including parsing and rendering encrypted file-message payloads as local images, inline Media3 video players, or downloadable file attachments.
- `android/app/src/main/java/niel/kro/penik/ui/theme/Theme.kt` — Material/Compose app theme and color scheme.
- `android/app/src/main/java/niel/kro/penik/ui/theme/Color.kt` — Android UI color palette.
- `android/app/src/main/java/niel/kro/penik/ui/theme/Type.kt` — Compose UI typography.

## Crypto

- `client/js/crypto.js` — Implements browser cryptography: X25519, HKDF, ChaCha20-Poly1305, direct message E2EE, signatures, safety numbers, key backups, and group encryption.
- `client/js/groups.js` — Coordinates client-side group E2EE: epoch key generation, wrapping envelopes for devices, rotation, message encryption, and history synchronization.
- `android/app/src/main/java/niel/kro/penik/data/crypto/E2EECrypto.kt` — Implements Android E2EE using X25519, HKDF, and ChaCha20-Poly1305, including encryption/decryption of private key backups.
- `android/app/src/main/java/niel/kro/penik/data/crypto/GroupCrypto.kt` — Implements group encryption, AAD protocol, derivation of message keys, and wrapping/unwrapping group keys for devices.
- `android/app/src/main/java/niel/kro/penik/data/local/database/DatabaseEncryption.kt` — Prepares the Android local database encryption key and handles migration for previously unencrypted DBs.
- `android/app/src/main/java/niel/kro/penik/data/repository/AuthRepository.kt` — Generates and persists stable identity key pairs during register/login, and handles upload/restore of encrypted key backups.
- `server/internal/models/keys.go` — Defines server models for identity keys, one-time keys, key backups, and device key bundles.
- `server/internal/handlers/keys.go` — Receives and serves public keys and opaque backup blobs without decrypting client secrets.

## Data

### Server persistence and domain data

- `server/internal/db/schema.sql` — Canonical SQLite schema for users, devices, chats, messages, sessions, pairing, groups, key envelopes, and group history.
- `server/internal/db/db.go` — Opens SQLite with WAL/foreign keys, applies the schema, and runs legacy structure migrations.
- `server/internal/models/user.go` — Models for users and public server profiles.
- `server/internal/models/device.go` — Models for user devices and runtime metadata.
- `server/internal/models/message.go` — Models for direct messages and delivery/read/deletion statuses.
- `server/internal/handlers/group_keys.go` — Stores and serves server records of group key versions and encrypted envelopes.

### Android local data

- `android/app/src/main/java/niel/kro/penik/data/local/database/PenikDatabase.kt` — Room database for the Android client, unifying chats, groups, keys, and messages entities, including schema migrations.
- `android/app/src/main/java/niel/kro/penik/data/local/entity/Entities.kt` — Room entities for local messages, chats, groups, members, keys, and group messages.
- `android/app/src/main/java/niel/kro/penik/data/local/dao/MessageDao.kt` — DAO for reading/writing direct messages, history, and statuses.
- `android/app/src/main/java/niel/kro/penik/data/local/dao/ChatDao.kt` — DAO for the chat list, contacts, unread counters, and last messages.
- `android/app/src/main/java/niel/kro/penik/data/local/dao/GroupDao.kt` — DAO for groups, members, group keys, and group messages.
- `android/app/src/main/java/niel/kro/penik/data/repository/MessageRepository.kt` — Synchronizes history, handles direct messages/WebSocket events, encrypts/decrypts payloads, and persists to Room.
- `android/app/src/main/java/niel/kro/penik/data/repository/ChatRepository.kt` — Repository for the direct chat list and aggregated contact/last message data.
- `android/app/src/main/java/niel/kro/penik/data/repository/GroupRepository.kt` — Synchronizes groups/members, stores group keys/messages, manages envelopes, rotation, and group history.
- `android/app/src/main/java/niel/kro/penik/data/repository/PresenceBus.kt` — Shared flow of presence updates for UI observers and repositories.
- `android/app/src/main/java/niel/kro/penik/data/repository/AvatarCacheBus.kt` — Invalidates locally cached avatars after server updates.
- `android/app/src/main/java/niel/kro/penik/data/di/Modules.kt` — Hilt providers for the local DB, SQLCipher, Retrofit/OkHttp, API, WebSocket, and crypto dependencies.

### Shared application/domain coordination

- `android/app/src/main/java/niel/kro/penik/domain/model/Models.kt` — Domain models for users, chats, messages, and auth results, used above the data layer.
- `android/app/src/main/java/niel/kro/penik/domain/usecase/UseCases.kt` — Application use cases (login, register, send, sync, logout) and central handling of WebSocket events updating repositories.
- `android/app/src/main/java/niel/kro/penik/domain/WebSocketEventCoordinator.kt` — Continuously listens to the WebSocket event flow and routes to use cases independently of the screen lifecycle.

### Project configuration

- `server/go.mod` — Describes the Go server module and its dependencies.
- `client/package.json` — Describes npm scripts, dependencies, and build config for the web client.
- `client/vite.config.js` — Vite configuration for development and production build of the browser client.
- `client/tsconfig.json` — Type-check-only TypeScript config for the browser sources: `checkJs` over plain JavaScript with `noEmit`, driven by `npm run typecheck`.
- `client/tsconfig.sw.json` — Separate type-check config for the service worker, which needs the WebWorker lib instead of DOM.
- `android/settings.gradle.kts` — Configures the Android Gradle project and its modules.
- `android/build.gradle.kts` — Root Gradle configuration for the Android project.
- `android/app/build.gradle.kts` — Configuration for the Android app, SDK, Compose, Media3 playback, Hilt, Room, SQLCipher, and network dependencies.

### Documentation

- `README.md` — Root project overview: stack, repository layout, build and run instructions for server/web/Android, configuration reference, architecture summary (E2EE, pairing, WebSocket transport, storage, attachments), rate limits, test commands, and known security limitations.
- `server/README.md` — Server-side details: Go backend stack, environment configuration, REST endpoints, and WebSocket opcode table.
- `PROJECT_MAP.md` — This index of core source files and their purpose.
- `SECURITY_AUDIT.md` — Security audit report with a registry of findings and their remediation status.
- `AUDIT.md` — Review of the browser client's cryptographic implementation.
- `Docs/README.md` — Documentation sitemap and navigation index.
- `Docs/REST_API.md` — Full REST API reference: auth, profiles, keys, pairing, groups, attachments, and rate limits.
- `Docs/WEBSOCKET.md` — Complete WebSocket protocol reference: connection upgrade, binary framing, opcodes (0x01-0x28), and MsgPack payloads.
- `Docs/ARCHITECTURE.md` — Deep dive into E2EE (X3DH/ChaCha20-Poly1305), epoch-based group encryption, device pairing, VK CDN attachment proxying, and multi-tier persistence.

