package org.example.game;

import org.example.model.*;
import org.example.network.NetworkManager;
import org.example.SnakesProto;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class GameController {

    private static final String MULTICAST_ADDRESS = "239.192.0.4";
    private static final int MULTICAST_PORT = 9192;
    private final Map<InetSocketAddress, Integer> processedJoins;
    private volatile GameState gameState;
    private volatile GameConfig config;
    private volatile NodeRole myRole;
    private volatile int myId;
    private volatile String myName;
    private volatile String gameName;

    private final NetworkManager networkManager;
    private final GameLogic gameLogic;

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> gameLoopTask;
    private ScheduledFuture<?> announcementTask;
    private ScheduledFuture<?> timeoutCheckTask;
    private ScheduledFuture<?> discoveryTask;

    private final Map<Integer, Direction> pendingMoves;
    private final Map<Long, PendingMessage> unackedMessages;
    private final AtomicLong msgSeqCounter;
    private final AtomicInteger playerIdCounter;

    private volatile InetSocketAddress masterAddress;
    private volatile InetSocketAddress deputyAddress;

    private Consumer<GameState> stateUpdateCallback;
    private Consumer<String> errorCallback;
    private Consumer<List<GameInfo>> gamesListCallback;

    private final Map<String, GameInfo> availableGames;
    private volatile long lastMasterActivity;

    private final Map<Integer, Long> playerLastActivity;

    // Кэш адресов - используем адреса из proto ИЛИ реальные адреса входящих пакетов
    private final Map<Integer, InetSocketAddress> knownPlayerAddresses;

    public GameController() {
        this.networkManager = new NetworkManager(this::handleMessage);
        this.gameLogic = new GameLogic();
        this.scheduler = Executors.newScheduledThreadPool(4);
        this.pendingMoves = new ConcurrentHashMap<>();
        this.unackedMessages = new ConcurrentHashMap<>();
        this.msgSeqCounter = new AtomicLong(0);
        this.playerIdCounter = new AtomicInteger(1);
        this.availableGames = new ConcurrentHashMap<>();
        this.playerLastActivity = new ConcurrentHashMap<>();
        this.processedJoins = new ConcurrentHashMap<>();
        this.knownPlayerAddresses = new ConcurrentHashMap<>();
    }

    public void setStateUpdateCallback(Consumer<GameState> callback) {
        this.stateUpdateCallback = callback;
    }

    public void setErrorCallback(Consumer<String> callback) {
        this.errorCallback = callback;
    }

    public void setGamesListCallback(Consumer<List<GameInfo>> callback) {
        this.gamesListCallback = callback;
    }

    public void startNewGame(String playerName, String gameName, GameConfig config) {
        resetClientState();

        this.config = config;
        this.gameName = gameName;
        this.myName = playerName;
        this.myRole = NodeRole.MASTER;
        this.myId = playerIdCounter.getAndIncrement();

        this.gameState = new GameState(config);

        Player player = new Player(myId, playerName, NodeRole.MASTER);
        gameState.addPlayer(player);
        playerLastActivity.put(myId, System.currentTimeMillis());

        Snake snake = gameLogic.createSnakeForPlayer(gameState, myId);
        if (snake != null) {
            gameState.addSnake(snake);
        }

        gameState.spawnFood();

        if (!networkManager.isRunning()) {
            networkManager.start();
        }

        System.out.println("[GAME] Started new game: " + gameName + " as MASTER, player " + myId);

        startGameLoop();
        startAnnouncement();
        startTimeoutChecker();
    }

    public void joinGame(String playerName, GameInfo gameInfo, boolean viewerOnly) {
        this.myName = playerName;
        this.gameName = gameInfo.getName();
        this.config = gameInfo.getConfig();
        this.myRole = viewerOnly ? NodeRole.VIEWER : NodeRole.NORMAL;
        this.masterAddress = gameInfo.getMasterAddress();
        this.lastMasterActivity = System.currentTimeMillis();

        networkManager.start();
        startTimeoutChecker();

        SnakesProto.GameMessage.JoinMsg joinMsg = SnakesProto.GameMessage.JoinMsg.newBuilder()
                .setPlayerName(playerName)
                .setGameName(gameInfo.getName())
                .setRequestedRole(viewerOnly ? SnakesProto.NodeRole.VIEWER : SnakesProto.NodeRole.NORMAL)
                .build();

        sendMessage(masterAddress, SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeqCounter.getAndIncrement())
                .setJoin(joinMsg)
                .build());

        System.out.println("[GAME] Joining game: " + gameName + " at " + masterAddress);
    }

    public void steer(Direction direction) {
        if (myRole == NodeRole.VIEWER) return;

        if (myRole == NodeRole.MASTER) {
            pendingMoves.put(myId, direction);
        } else if (masterAddress != null) {
            SnakesProto.GameMessage.SteerMsg steerMsg = SnakesProto.GameMessage.SteerMsg.newBuilder()
                    .setDirection(toProtoDirection(direction))
                    .build();

            sendMessage(masterAddress, SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setSteer(steerMsg)
                    .build());
        }
    }

    private void resetClientState() {
        myId = 0;
        myRole = null;
        myName = null;
        gameName = null;
        config = null;
        gameState = null;
        masterAddress = null;
        deputyAddress = null;
        lastMasterActivity = 0;

        pendingMoves.clear();
        unackedMessages.clear();
        playerLastActivity.clear();
        processedJoins.clear();
        availableGames.clear();
        knownPlayerAddresses.clear();

        msgSeqCounter.set(0);

        System.out.println("[GAME] Client state reset");
    }

    public void leaveGame() {
        System.out.println("[GAME] Leaving game, myRole=" + myRole + ", myId=" + myId);

        if (myRole == NodeRole.MASTER && gameState != null) {
            transferMasterRoleBeforeLeave();
        } else if (myRole != null && masterAddress != null && myId > 0) {
            try {
                SnakesProto.GameMessage.RoleChangeMsg roleChange = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                        .setSenderRole(SnakesProto.NodeRole.VIEWER)
                        .build();

                networkManager.send(SnakesProto.GameMessage.newBuilder()
                        .setMsgSeq(msgSeqCounter.getAndIncrement())
                        .setSenderId(myId)
                        .setRoleChange(roleChange)
                        .build(), masterAddress);
            } catch (Exception e) {
                System.err.println("[GAME] Error sending leave message: " + e.getMessage());
            }
        }

        if (gameLoopTask != null) {
            gameLoopTask.cancel(false);
            gameLoopTask = null;
        }
        if (announcementTask != null) {
            announcementTask.cancel(false);
            announcementTask = null;
        }
        if (timeoutCheckTask != null) {
            timeoutCheckTask.cancel(false);
            timeoutCheckTask = null;
        }

        resetClientState();
        networkManager.restart();

        System.out.println("[GAME] Left game, state reset");
    }

    private void transferMasterRoleBeforeLeave() {
        System.out.println("[GAME] Transferring MASTER role before leaving...");

        Player newMaster = findBestNewMaster();

        if (newMaster != null) {
            InetSocketAddress newMasterAddr = getPlayerAddress(newMaster.getId());
            System.out.println("[GAME] Transferring MASTER to player " + newMaster.getId() + " at " + newMasterAddr);

            newMaster.setRole(NodeRole.MASTER);
            Player me = gameState.getPlayer(myId);
            if (me != null) {
                me.setRole(NodeRole.VIEWER);
            }

            SnakesProto.GameMessage.RoleChangeMsg roleChange = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                    .setSenderRole(SnakesProto.NodeRole.VIEWER)
                    .setReceiverRole(SnakesProto.NodeRole.MASTER)
                    .build();

            networkManager.send(SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setReceiverId(newMaster.getId())
                    .setRoleChange(roleChange)
                    .build(), newMasterAddr);

            broadcastState();
        } else {
            System.out.println("[GAME] No one to transfer MASTER role to");
        }
    }

    private Player findBestNewMaster() {
        if (gameState == null) return null;

        for (Player player : gameState.getPlayers().values()) {
            if (player.getId() != myId &&
                    player.getRole() == NodeRole.DEPUTY &&
                    getPlayerAddress(player.getId()) != null) {
                return player;
            }
        }

        for (Player player : gameState.getPlayers().values()) {
            if (player.getId() != myId &&
                    player.getRole() == NodeRole.NORMAL &&
                    getPlayerAddress(player.getId()) != null) {
                return player;
            }
        }

        return null;
    }

    private InetSocketAddress getPlayerAddress(int playerId) {
        return knownPlayerAddresses.get(playerId);
    }

    public void shutdown() {
        stopDiscovery();
        stopGame();
        scheduler.shutdownNow();
        networkManager.stop();
    }

    public void startDiscovery() {
        stopDiscovery();

        if (!networkManager.isRunning()) {
            networkManager.start();
        }

        networkManager.startMulticastReceiver(MULTICAST_ADDRESS, MULTICAST_PORT);
        availableGames.clear();

        discoveryTask = scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            availableGames.entrySet().removeIf(e -> now - e.getValue().getLastSeen() > 3000);

            if (gamesListCallback != null) {
                gamesListCallback.accept(new ArrayList<>(availableGames.values()));
            }
        }, 0, 1, TimeUnit.SECONDS);

        System.out.println("[GAME] Discovery started");
    }

    public void stopDiscovery() {
        if (discoveryTask != null) {
            discoveryTask.cancel(false);
            discoveryTask = null;
        }
        networkManager.stopMulticastReceiver();
        availableGames.clear();
        System.out.println("[GAME] Discovery stopped");
    }

    public GameState getGameState() {
        return gameState;
    }

    public NodeRole getMyRole() {
        return myRole;
    }

    public int getMyId() {
        return myId;
    }

    private void startGameLoop() {
        if (gameLoopTask != null) {
            gameLoopTask.cancel(false);
        }

        gameLoopTask = scheduler.scheduleAtFixedRate(this::gameTick,
                config.getStateDelayMs(), config.getStateDelayMs(), TimeUnit.MILLISECONDS);
    }

    private void startTimeoutChecker() {
        if (timeoutCheckTask != null) {
            timeoutCheckTask.cancel(false);
        }

        long interval = config != null ? config.getStateDelayMs() / 10 : 100;
        timeoutCheckTask = scheduler.scheduleAtFixedRate(this::checkTimeouts,
                interval, interval, TimeUnit.MILLISECONDS);
    }

    private void startAnnouncement() {
        if (announcementTask != null) {
            announcementTask.cancel(false);
        }

        announcementTask = scheduler.scheduleAtFixedRate(this::sendAnnouncement,
                0, 1, TimeUnit.SECONDS);
    }

    private void stopGame() {
        if (gameLoopTask != null) {
            gameLoopTask.cancel(false);
            gameLoopTask = null;
        }
        if (announcementTask != null) {
            announcementTask.cancel(false);
            announcementTask = null;
        }
        if (timeoutCheckTask != null) {
            timeoutCheckTask.cancel(false);
            timeoutCheckTask = null;
        }

        gameState = null;
        myRole = null;
        myId = 0;
        masterAddress = null;
        deputyAddress = null;
        playerLastActivity.clear();
        processedJoins.clear();
        pendingMoves.clear();
        unackedMessages.clear();
        knownPlayerAddresses.clear();
    }

    private void gameTick() {
        if (myRole != NodeRole.MASTER || gameState == null) {
            return;
        }

        try {
            List<Integer> deadPlayers = gameLogic.tick(gameState, pendingMoves);
            pendingMoves.clear();

            boolean iDied = false;

            for (int playerId : deadPlayers) {
                Player player = gameState.getPlayer(playerId);
                if (player != null) {
                    NodeRole oldRole = player.getRole();
                    player.setRole(NodeRole.VIEWER);

                    System.out.println("[GAME] Player " + playerId + " died, was " + oldRole + ", now VIEWER");

                    if (oldRole == NodeRole.DEPUTY) {
                        deputyAddress = null;
                    }

                    InetSocketAddress playerAddr = getPlayerAddress(playerId);
                    if (playerAddr != null && playerId != myId) {
                        SnakesProto.GameMessage.RoleChangeMsg roleChangeMsg = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                                .setReceiverRole(SnakesProto.NodeRole.VIEWER)
                                .build();

                        sendMessage(playerAddr, SnakesProto.GameMessage.newBuilder()
                                .setMsgSeq(msgSeqCounter.getAndIncrement())
                                .setSenderId(myId)
                                .setReceiverId(playerId)
                                .setRoleChange(roleChangeMsg)
                                .build());
                    }

                    if (playerId == myId) {
                        iDied = true;
                    }
                }
            }

            if (iDied) {
                System.out.println("[GAME] I (MASTER) died!");

                Player me = gameState.getPlayer(myId);
                if (me != null) {
                    me.setRole(NodeRole.VIEWER);
                }

                ensureDeputy();
                broadcastState();
                promoteNewMaster();
            } else {
                ensureDeputy();
                broadcastState();
            }

            if (stateUpdateCallback != null) {
                stateUpdateCallback.accept(gameState);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void promoteNewMaster() {
        if (gameState == null) return;

        System.out.println("[GAME] === PROMOTE NEW MASTER ===");
        System.out.println("[GAME] Known addresses:");
        for (Map.Entry<Integer, InetSocketAddress> entry : knownPlayerAddresses.entrySet()) {
            System.out.println("[GAME]   Player " + entry.getKey() + " -> " + entry.getValue());
        }

        Player newMaster = findBestNewMaster();

        if (newMaster != null) {
            InetSocketAddress newMasterAddr = getPlayerAddress(newMaster.getId());
            System.out.println("[GAME] Promoting player " + newMaster.getId() + " at " + newMasterAddr);

            newMaster.setRole(NodeRole.MASTER);

            Player me = gameState.getPlayer(myId);
            if (me != null) {
                me.setRole(NodeRole.VIEWER);
            }
            myRole = NodeRole.VIEWER;

            for (Player player : gameState.getPlayers().values()) {
                if (player.getId() != myId && player.getId() != newMaster.getId() &&
                        player.getRole() == NodeRole.DEPUTY) {
                    player.setRole(NodeRole.NORMAL);
                }
            }

            SnakesProto.GameMessage.RoleChangeMsg roleChange = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                    .setSenderRole(SnakesProto.NodeRole.VIEWER)
                    .setReceiverRole(SnakesProto.NodeRole.MASTER)
                    .build();

            sendMessage(newMasterAddr, SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setReceiverId(newMaster.getId())
                    .setRoleChange(roleChange)
                    .build());

            broadcastState();

            masterAddress = newMasterAddr;
            deputyAddress = null;
            lastMasterActivity = System.currentTimeMillis();

            if (gameLoopTask != null) {
                gameLoopTask.cancel(false);
                gameLoopTask = null;
            }
            if (announcementTask != null) {
                announcementTask.cancel(false);
                announcementTask = null;
            }

            System.out.println("[GAME] I am now VIEWER, new MASTER is " + newMaster.getId());

            if (stateUpdateCallback != null) {
                stateUpdateCallback.accept(gameState);
            }

            SnakesProto.GameMessage.JoinMsg joinAsViewer = SnakesProto.GameMessage.JoinMsg.newBuilder()
                    .setPlayerName(myName)
                    .setGameName(gameName)
                    .setRequestedRole(SnakesProto.NodeRole.VIEWER)
                    .build();

            sendMessage(masterAddress, SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setJoin(joinAsViewer)
                    .setSenderId(myId)
                    .build());
        } else {
            System.out.println("[GAME] No one to promote!");
        }
    }

    private void ensureDeputy() {
        if (myRole != NodeRole.MASTER || gameState == null) return;

        boolean hasDeputy = gameState.getPlayers().values().stream()
                .anyMatch(p -> p.getRole() == NodeRole.DEPUTY && p.getId() != myId);

        if (!hasDeputy) {
            for (Player player : gameState.getPlayers().values()) {
                if (player.getRole() == NodeRole.NORMAL && player.getId() != myId) {
                    InetSocketAddress playerAddr = getPlayerAddress(player.getId());
                    if (playerAddr != null) {
                        player.setRole(NodeRole.DEPUTY);
                        deputyAddress = playerAddr;

                        SnakesProto.GameMessage.RoleChangeMsg roleChange = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                                .setReceiverRole(SnakesProto.NodeRole.DEPUTY)
                                .build();

                        sendMessage(playerAddr, SnakesProto.GameMessage.newBuilder()
                                .setMsgSeq(msgSeqCounter.getAndIncrement())
                                .setSenderId(myId)
                                .setReceiverId(player.getId())
                                .setRoleChange(roleChange)
                                .build());

                        System.out.println("[GAME] Assigned DEPUTY role to player " + player.getId());
                        break;
                    }
                }
            }
        }
    }

    private void broadcastState() {
        if (gameState == null) return;

        SnakesProto.GameState protoState = buildProtoState();

        SnakesProto.GameMessage.StateMsg stateMsg = SnakesProto.GameMessage.StateMsg.newBuilder()
                .setState(protoState)
                .build();

        System.out.println("[GAME] Broadcasting state #" + gameState.getStateOrder());

        int sent = 0;
        for (Player player : gameState.getPlayers().values()) {
            if (player.getId() == myId) {
                continue;
            }

            InetSocketAddress playerAddr = getPlayerAddress(player.getId());
            if (playerAddr == null) {
                System.out.println("[GAME]   Player " + player.getId() + " - NO ADDRESS");
                continue;
            }

            System.out.println("[GAME]   -> Player " + player.getId() + " at " + playerAddr);

            sendMessage(playerAddr, SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setReceiverId(player.getId())
                    .setState(stateMsg)
                    .build());
            sent++;
        }

        System.out.println("[GAME] Sent to " + sent + " players");
    }

    private void sendAnnouncement() {
        if (myRole != NodeRole.MASTER || gameState == null) return;

        try {
            SnakesProto.GameAnnouncement announcement = buildAnnouncement();

            SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setAnnouncement(SnakesProto.GameMessage.AnnouncementMsg.newBuilder()
                            .addGames(announcement)
                            .build())
                    .build();

            networkManager.sendMulticast(msg, MULTICAST_ADDRESS, MULTICAST_PORT);
            networkManager.sendBroadcast(msg, MULTICAST_PORT);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendMessage(InetSocketAddress address, SnakesProto.GameMessage msg) {
        if (address == null) {
            System.err.println("[GAME] sendMessage: address is null!");
            return;
        }

        networkManager.send(msg, address);

        if (!msg.hasAck() && !msg.hasAnnouncement() && !msg.hasDiscover()) {
            unackedMessages.put(msg.getMsgSeq(), new PendingMessage(msg, address, System.currentTimeMillis()));
        }
    }

    private void checkTimeouts() {
        if (config == null) return;

        long now = System.currentTimeMillis();
        long resendInterval = config.getStateDelayMs() / 10;
        long nodeTimeout = (long) (config.getStateDelayMs() * 0.8);

        Iterator<Map.Entry<Long, PendingMessage>> it = unackedMessages.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, PendingMessage> entry = it.next();
            PendingMessage pm = entry.getValue();

            if (now - pm.createdTime > 5000) {
                it.remove();
                continue;
            }

            if (now - pm.sentTime > resendInterval) {
                networkManager.send(pm.message, pm.address);
                pm.sentTime = now;
            }
        }

        if (myRole == NodeRole.MASTER && gameState != null) {
            List<Integer> timedOut = new ArrayList<>();

            for (Player player : gameState.getPlayers().values()) {
                if (player.getId() == myId) continue;

                Long lastActivity = playerLastActivity.get(player.getId());
                if (lastActivity == null) {
                    playerLastActivity.put(player.getId(), now);
                    continue;
                }

                if (now - lastActivity > nodeTimeout * 3) {
                    timedOut.add(player.getId());
                }
            }

            for (int playerId : timedOut) {
                handlePlayerTimeout(playerId);
            }
        }

        if (myRole == NodeRole.DEPUTY && lastMasterActivity > 0) {
            if (now - lastMasterActivity > nodeTimeout * 3) {
                System.out.println("[GAME] MASTER timeout, promoting myself");
                promoteToMaster();
            }
        }

        if (myRole == NodeRole.NORMAL && lastMasterActivity > 0) {
            if (now - lastMasterActivity > nodeTimeout * 5) {
                System.out.println("[GAME] NORMAL: MASTER timeout, waiting...");
                lastMasterActivity = now;
            }
        }
    }

    private void handlePlayerTimeout(int playerId) {
        Player player = gameState.getPlayer(playerId);
        if (player == null) return;

        System.out.println("[GAME] Player " + playerId + " timed out");

        InetSocketAddress playerAddr = getPlayerAddress(playerId);
        if (playerAddr != null) {
            processedJoins.remove(playerAddr);
        }

        if (player.getRole() == NodeRole.DEPUTY) {
            deputyAddress = null;
        }

        Snake snake = gameState.getSnake(playerId);
        if (snake != null && snake.getState() == Snake.SnakeState.ALIVE) {
            snake.setState(Snake.SnakeState.ZOMBIE);
        }

        player.setRole(NodeRole.VIEWER);
        playerLastActivity.remove(playerId);

        ensureDeputy();
    }

    private void promoteToMaster() {
        System.out.println("[GAME] === PROMOTING TO MASTER ===");
        System.out.println("[GAME] Known addresses:");
        for (Map.Entry<Integer, InetSocketAddress> entry : knownPlayerAddresses.entrySet()) {
            System.out.println("[GAME]   Player " + entry.getKey() + " -> " + entry.getValue());
        }

        myRole = NodeRole.MASTER;

        if (gameState != null) {
            Player me = gameState.getPlayer(myId);
            if (me != null) {
                me.setRole(NodeRole.MASTER);
            }

            long now = System.currentTimeMillis();
            for (Player player : gameState.getPlayers().values()) {
                if (player.getId() != myId) {
                    playerLastActivity.put(player.getId(), now);
                }
            }

            for (Player player : gameState.getPlayers().values()) {
                if (player.getId() == myId) continue;

                InetSocketAddress playerAddr = getPlayerAddress(player.getId());
                if (playerAddr == null) {
                    System.out.println("[GAME] No address for player " + player.getId());
                    continue;
                }

                SnakesProto.GameMessage.RoleChangeMsg.Builder roleChangeBuilder =
                        SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                                .setSenderRole(SnakesProto.NodeRole.MASTER);

                sendMessage(playerAddr, SnakesProto.GameMessage.newBuilder()
                        .setMsgSeq(msgSeqCounter.getAndIncrement())
                        .setSenderId(myId)
                        .setReceiverId(player.getId())
                        .setRoleChange(roleChangeBuilder.build())
                        .build());

                System.out.println("[GAME] Notified player " + player.getId());
            }
        }

        masterAddress = null;
        deputyAddress = null;

        startGameLoop();
        startAnnouncement();
        ensureDeputy();

        if (gameState != null) {
            broadcastState();
        }

        System.out.println("[GAME] I am now MASTER");
    }

    private void updatePlayerActivity(int playerId) {
        playerLastActivity.put(playerId, System.currentTimeMillis());
    }

    // Сохраняем реальный адрес (от входящего пакета) - имеет приоритет
    private void updatePlayerAddressFromPacket(int playerId, InetSocketAddress address) {
        if (address == null || playerId <= 0) return;
        knownPlayerAddresses.put(playerId, address);
        System.out.println("[GAME] Address from packet: player " + playerId + " -> " + address);
    }

    // Сохраняем адрес из proto (если нет реального)
    private void updatePlayerAddressFromProto(int playerId, InetSocketAddress address) {
        if (address == null || playerId <= 0) return;
        // Только если ещё не знаем адрес этого игрока
        if (!knownPlayerAddresses.containsKey(playerId)) {
            knownPlayerAddresses.put(playerId, address);
            System.out.println("[GAME] Address from proto: player " + playerId + " -> " + address);
        }
    }

    private void handleMessage(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        try {
            // Сохраняем реальный адрес отправителя
            if (msg.hasSenderId() && msg.getSenderId() > 0) {
                updatePlayerAddressFromPacket(msg.getSenderId(), sender);
                updatePlayerActivity(msg.getSenderId());
            }

            if (msg.hasAck()) {
                handleAck(msg, sender);
            } else if (msg.hasAnnouncement()) {
                handleAnnouncement(msg, sender);
            } else if (msg.hasJoin()) {
                handleJoin(msg, sender);
            } else if (msg.hasSteer()) {
                handleSteer(msg, sender);
            } else if (msg.hasState()) {
                handleState(msg, sender);
            } else if (msg.hasPing()) {
                handlePing(msg, sender);
            } else if (msg.hasRoleChange()) {
                handleRoleChange(msg, sender);
            } else if (msg.hasError()) {
                handleError(msg);
            } else if (msg.hasDiscover()) {
                handleDiscover(msg, sender);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleAck(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        unackedMessages.remove(msg.getMsgSeq());

        if (msg.hasReceiverId() && msg.getReceiverId() > 0 && myId <= 0) {
            myId = msg.getReceiverId();
            System.out.println("[GAME] Received my ID: " + myId);
        }

        if (msg.hasSenderId() && msg.getSenderId() > 0) {
            updatePlayerAddressFromPacket(msg.getSenderId(), sender);
            updatePlayerActivity(msg.getSenderId());
        }
    }

    private void handleAnnouncement(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        for (SnakesProto.GameAnnouncement ann : msg.getAnnouncement().getGamesList()) {
            GameInfo info = new GameInfo(
                    ann.getGameName(),
                    sender,
                    fromProtoConfig(ann.getConfig()),
                    ann.getPlayers().getPlayersCount(),
                    ann.getCanJoin()
            );
            availableGames.put(ann.getGameName(), info);
        }

        if (gamesListCallback != null) {
            gamesListCallback.accept(new ArrayList<>(availableGames.values()));
        }
    }

    private void handleJoin(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        if (myRole != NodeRole.MASTER) return;

        SnakesProto.GameMessage.JoinMsg join = msg.getJoin();
        System.out.println("[GAME] ========================================");
        System.out.println("[GAME] Join request received!");
        System.out.println("[GAME] Sender address from packet: " + sender);
        System.out.println("[GAME] Sender IP: " + sender.getAddress().getHostAddress());
        System.out.println("[GAME] Sender port: " + sender.getPort());
        System.out.println("[GAME] Player name: " + join.getPlayerName());
        System.out.println("[GAME] ========================================");
        System.out.println("[GAME] Join from " + sender + ", name=" + join.getPlayerName());

        Integer existingPlayerId = processedJoins.get(sender);
        if (existingPlayerId != null) {
            Player existingPlayer = gameState.getPlayer(existingPlayerId);
            if (existingPlayer != null && existingPlayer.getRole() != NodeRole.VIEWER) {
                sendAck(sender, msg.getMsgSeq(), myId, existingPlayerId);
                return;
            }
        }

        if (msg.hasSenderId() && msg.getSenderId() > 0) {
            int senderId = msg.getSenderId();
            Player existingPlayer = gameState.getPlayer(senderId);

            if (existingPlayer != null) {
                System.out.println("[GAME] Reconnecting player " + senderId);
                knownPlayerAddresses.put(senderId, sender);
                playerLastActivity.put(senderId, System.currentTimeMillis());
                processedJoins.put(sender, senderId);

                if (join.getRequestedRole() == SnakesProto.NodeRole.VIEWER) {
                    existingPlayer.setRole(NodeRole.VIEWER);
                }

                sendAck(sender, msg.getMsgSeq(), myId, senderId);

                SnakesProto.GameMessage.StateMsg stateMsg = SnakesProto.GameMessage.StateMsg.newBuilder()
                        .setState(buildProtoState())
                        .build();
                sendMessage(sender, SnakesProto.GameMessage.newBuilder()
                        .setMsgSeq(msgSeqCounter.getAndIncrement())
                        .setSenderId(myId)
                        .setReceiverId(senderId)
                        .setState(stateMsg)
                        .build());
                return;
            }
        }

        if (join.getRequestedRole() == SnakesProto.NodeRole.NORMAL ||
                join.getRequestedRole() == SnakesProto.NodeRole.VIEWER) {

            int newId = playerIdCounter.getAndIncrement();
            NodeRole role = fromProtoRole(join.getRequestedRole());

            Player newPlayer = new Player(newId, join.getPlayerName(), role);
            gameState.addPlayer(newPlayer);
            playerLastActivity.put(newId, System.currentTimeMillis());
            processedJoins.put(sender, newId);
            knownPlayerAddresses.put(newId, sender);

            System.out.println("[GAME] New player " + newId + " (" + role + ") at " + sender);

            if (role != NodeRole.VIEWER) {
                Snake snake = gameLogic.createSnakeForPlayer(gameState, newId);
                if (snake == null) {
                    gameState.removePlayer(newId);
                    playerLastActivity.remove(newId);
                    processedJoins.remove(sender);
                    knownPlayerAddresses.remove(newId);
                    sendError(sender, "No room for new snake", msg.getMsgSeq());
                    return;
                }
                gameState.addSnake(snake);
            }

            sendAck(sender, msg.getMsgSeq(), myId, newId);

            SnakesProto.GameMessage.StateMsg stateMsg = SnakesProto.GameMessage.StateMsg.newBuilder()
                    .setState(buildProtoState())
                    .build();

            sendMessage(sender, SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setReceiverId(newId)
                    .setState(stateMsg)
                    .build());

            ensureDeputy();
        }
    }

    private void handleSteer(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        if (myRole != NodeRole.MASTER) return;

        int senderId = msg.getSenderId();
        Direction dir = fromProtoDirection(msg.getSteer().getDirection());

        pendingMoves.put(senderId, dir);
        updatePlayerActivity(senderId);

        sendAck(sender, msg.getMsgSeq(), myId, senderId);
    }

    private void handleState(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        if (myRole == NodeRole.MASTER) {
            sendAck(sender, msg.getMsgSeq(), myId, msg.getSenderId());
            return;
        }

        lastMasterActivity = System.currentTimeMillis();
        masterAddress = sender;

        SnakesProto.GameState protoState = msg.getState().getState();

        if (gameState != null && protoState.getStateOrder() <= gameState.getStateOrder()) {
            sendAck(sender, msg.getMsgSeq(), myId, msg.getSenderId());
            return;
        }

        System.out.println("[GAME] State #" + protoState.getStateOrder() + " from " + sender);

        gameState = fromProtoState(protoState, config != null ? config : GameConfig.defaultConfig());

        // Синхронизируем роль
        if (myId > 0) {
            Player me = gameState.getPlayer(myId);
            if (me != null && myRole != me.getRole()) {
                System.out.println("[GAME] Role sync: " + myRole + " -> " + me.getRole());
                myRole = me.getRole();
            }
        }

        // Обновляем DEPUTY адрес
        deputyAddress = null;
        for (Player p : gameState.getPlayers().values()) {
            if (p.getRole() == NodeRole.DEPUTY && p.getId() != myId) {
                deputyAddress = getPlayerAddress(p.getId());
                break;
            }
        }

        if (stateUpdateCallback != null) {
            stateUpdateCallback.accept(gameState);
        }

        sendAck(sender, msg.getMsgSeq(), myId, msg.getSenderId());
    }

    private void handlePing(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        if (msg.hasSenderId()) {
            updatePlayerActivity(msg.getSenderId());
        }
        sendAck(sender, msg.getMsgSeq(), myId, msg.hasSenderId() ? msg.getSenderId() : 0);
    }

    private void handleRoleChange(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        SnakesProto.GameMessage.RoleChangeMsg roleChange = msg.getRoleChange();

        System.out.println("[GAME] RoleChange from " + sender +
                ": senderRole=" + (roleChange.hasSenderRole() ? roleChange.getSenderRole() : "none") +
                ", receiverRole=" + (roleChange.hasReceiverRole() ? roleChange.getReceiverRole() : "none"));

        if (msg.hasReceiverId() && msg.getReceiverId() != myId) {
            if (msg.hasSenderId()) {
                sendAck(sender, msg.getMsgSeq(), myId, msg.getSenderId());
            }
            return;
        }

        boolean needsUiUpdate = false;

        if (roleChange.hasReceiverRole()) {
            NodeRole newRole = fromProtoRole(roleChange.getReceiverRole());
            NodeRole oldRole = myRole;

            System.out.println("[GAME] My role: " + oldRole + " -> " + newRole);
            myRole = newRole;
            needsUiUpdate = true;

            if (newRole == NodeRole.MASTER && oldRole != NodeRole.MASTER) {
                System.out.println("[GAME] === BECOMING MASTER ===");
                System.out.println("[GAME] Known addresses:");
                for (Map.Entry<Integer, InetSocketAddress> entry : knownPlayerAddresses.entrySet()) {
                    System.out.println("[GAME]   " + entry.getKey() + " -> " + entry.getValue());
                }

                if (gameState != null) {
                    Player me = gameState.getPlayer(myId);
                    if (me != null) {
                        me.setRole(NodeRole.MASTER);
                    }

                    if (msg.hasSenderId() && msg.getSenderId() != myId) {
                        Player oldMaster = gameState.getPlayer(msg.getSenderId());
                        if (oldMaster != null) {
                            oldMaster.setRole(NodeRole.VIEWER);
                        }
                    }

                    for (Player player : gameState.getPlayers().values()) {
                        if (player.getId() != myId && player.getRole() == NodeRole.DEPUTY) {
                            player.setRole(NodeRole.NORMAL);
                        }
                    }

                    long now = System.currentTimeMillis();
                    for (Player player : gameState.getPlayers().values()) {
                        if (player.getId() != myId) {
                            playerLastActivity.put(player.getId(), now);
                        }
                    }
                }

                masterAddress = null;
                deputyAddress = null;

                startGameLoop();
                startAnnouncement();
                ensureDeputy();

                if (gameState != null) {
                    System.out.println("[GAME] Broadcasting first state as MASTER");
                    broadcastState();
                }

            } else if (newRole == NodeRole.DEPUTY) {
                if (gameState != null) {
                    Player me = gameState.getPlayer(myId);
                    if (me != null) me.setRole(NodeRole.DEPUTY);
                }

            } else if (newRole == NodeRole.VIEWER) {
                if (gameState != null) {
                    Player me = gameState.getPlayer(myId);
                    if (me != null) me.setRole(NodeRole.VIEWER);
                }
            }
        }

        if (roleChange.hasSenderRole()) {
            SnakesProto.NodeRole senderRole = roleChange.getSenderRole();

            if (senderRole == SnakesProto.NodeRole.MASTER) {
                masterAddress = sender;
                lastMasterActivity = System.currentTimeMillis();
                System.out.println("[GAME] New MASTER at " + sender);

                if (msg.hasSenderId() && gameState != null) {
                    Player masterPlayer = gameState.getPlayer(msg.getSenderId());
                    if (masterPlayer != null) {
                        masterPlayer.setRole(NodeRole.MASTER);
                    }
                }
                needsUiUpdate = true;

            } else if (senderRole == SnakesProto.NodeRole.VIEWER) {
                if (myRole == NodeRole.MASTER && msg.hasSenderId()) {
                    handlePlayerLeave(msg.getSenderId());
                    needsUiUpdate = true;
                }
            }
        }

        if (needsUiUpdate && stateUpdateCallback != null && gameState != null) {
            stateUpdateCallback.accept(gameState);
        }

        if (msg.hasSenderId()) {
            sendAck(sender, msg.getMsgSeq(), myId, msg.getSenderId());
        }
    }

    private void handleError(SnakesProto.GameMessage msg) {
        System.err.println("[GAME] Error: " + msg.getError().getErrorMessage());
        if (errorCallback != null) {
            errorCallback.accept(msg.getError().getErrorMessage());
        }
    }

    private void handleDiscover(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        if (myRole == NodeRole.MASTER && gameState != null) {
            SnakesProto.GameAnnouncement announcement = buildAnnouncement();

            SnakesProto.GameMessage response = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setAnnouncement(SnakesProto.GameMessage.AnnouncementMsg.newBuilder()
                            .addGames(announcement)
                            .build())
                    .build();

            networkManager.send(response, sender);
        }
    }

    private void handlePlayerLeave(int playerId) {
        if (gameState == null) return;

        Player player = gameState.getPlayer(playerId);
        if (player == null) return;

        System.out.println("[GAME] Player " + playerId + " leaving");

        InetSocketAddress playerAddr = getPlayerAddress(playerId);
        if (playerAddr != null) {
            processedJoins.remove(playerAddr);
        }

        Snake snake = gameState.getSnake(playerId);
        if (snake != null) {
            snake.setState(Snake.SnakeState.ZOMBIE);
        }

        if (player.getRole() == NodeRole.DEPUTY) {
            deputyAddress = null;
            ensureDeputy();
        }

        player.setRole(NodeRole.VIEWER);
        playerLastActivity.remove(playerId);
    }

    private void sendAck(InetSocketAddress address, long msgSeq, int senderId, int receiverId) {
        SnakesProto.GameMessage ack = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq)
                .setSenderId(senderId)
                .setReceiverId(receiverId)
                .setAck(SnakesProto.GameMessage.AckMsg.getDefaultInstance())
                .build();

        networkManager.send(ack, address);
    }

    private void sendError(InetSocketAddress address, String message, long msgSeq) {
        SnakesProto.GameMessage error = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq)
                .setError(SnakesProto.GameMessage.ErrorMsg.newBuilder()
                        .setErrorMessage(message)
                        .build())
                .build();

        networkManager.send(error, address);
    }

    // ========== Proto conversion ==========

    private SnakesProto.GameState buildProtoState() {
        SnakesProto.GameState.Builder builder = SnakesProto.GameState.newBuilder()
                .setStateOrder(gameState.getStateOrder());

        for (Snake snake : gameState.getSnakes().values()) {
            SnakesProto.GameState.Snake.Builder snakeBuilder = SnakesProto.GameState.Snake.newBuilder()
                    .setPlayerId(snake.getPlayerId())
                    .setState(snake.getState() == Snake.SnakeState.ALIVE ?
                            SnakesProto.GameState.Snake.SnakeState.ALIVE :
                            SnakesProto.GameState.Snake.SnakeState.ZOMBIE)
                    .setHeadDirection(toProtoDirection(snake.getHeadDirection()));

            for (Coord point : snake.getKeyPoints()) {
                snakeBuilder.addPoints(SnakesProto.GameState.Coord.newBuilder()
                        .setX(point.getX())
                        .setY(point.getY())
                        .build());
            }

            builder.addSnakes(snakeBuilder.build());
        }

        for (Coord food : gameState.getFoods()) {
            builder.addFoods(SnakesProto.GameState.Coord.newBuilder()
                    .setX(food.getX())
                    .setY(food.getY())
                    .build());
        }

        SnakesProto.GamePlayers.Builder playersBuilder = SnakesProto.GamePlayers.newBuilder();
        for (Player player : gameState.getPlayers().values()) {
            SnakesProto.GamePlayer.Builder playerBuilder = SnakesProto.GamePlayer.newBuilder()
                    .setId(player.getId())
                    .setName(player.getName())
                    .setRole(toProtoRole(player.getRole()))
                    .setScore(player.getScore());

            // ВАЖНО: Включаем адреса в proto для передачи DEPUTY
            InetSocketAddress addr = getPlayerAddress(player.getId());
            if (addr != null) {
                playerBuilder.setIpAddress(addr.getAddress().getHostAddress());
                playerBuilder.setPort(addr.getPort());
            }

            playersBuilder.addPlayers(playerBuilder.build());
        }
        builder.setPlayers(playersBuilder.build());

        return builder.build();
    }

    private SnakesProto.GameAnnouncement buildAnnouncement() {
        SnakesProto.GamePlayers.Builder playersBuilder = SnakesProto.GamePlayers.newBuilder();
        for (Player player : gameState.getPlayers().values()) {
            if (player.getRole() != NodeRole.VIEWER) {
                playersBuilder.addPlayers(SnakesProto.GamePlayer.newBuilder()
                        .setId(player.getId())
                        .setName(player.getName())
                        .setRole(toProtoRole(player.getRole()))
                        .setScore(player.getScore())
                        .build());
            }
        }

        SnakesProto.GameConfig protoConfig = SnakesProto.GameConfig.newBuilder()
                .setWidth(config.getWidth())
                .setHeight(config.getHeight())
                .setFoodStatic(config.getFoodStatic())
                .setStateDelayMs(config.getStateDelayMs())
                .build();

        return SnakesProto.GameAnnouncement.newBuilder()
                .setGameName(gameName)
                .setPlayers(playersBuilder.build())
                .setConfig(protoConfig)
                .setCanJoin(gameState.findFreeSquare() != null)
                .build();
    }

    private GameState fromProtoState(SnakesProto.GameState protoState, GameConfig config) {
        GameState state = new GameState(config);
        state.setStateOrder(protoState.getStateOrder());

        for (SnakesProto.GameState.Snake protoSnake : protoState.getSnakesList()) {
            List<Coord> keyPoints = new ArrayList<>();
            for (SnakesProto.GameState.Coord coord : protoSnake.getPointsList()) {
                keyPoints.add(new Coord(coord.getX(), coord.getY()));
            }

            Snake snake = new Snake(
                    protoSnake.getPlayerId(),
                    keyPoints,
                    protoSnake.getState() == SnakesProto.GameState.Snake.SnakeState.ALIVE ?
                            Snake.SnakeState.ALIVE : Snake.SnakeState.ZOMBIE,
                    fromProtoDirection(protoSnake.getHeadDirection())
            );
            state.addSnake(snake);
        }

        for (SnakesProto.GameState.Coord food : protoState.getFoodsList()) {
            state.getFoods().add(new Coord(food.getX(), food.getY()));
        }

        for (SnakesProto.GamePlayer protoPlayer : protoState.getPlayers().getPlayersList()) {
            Player player = new Player(
                    protoPlayer.getId(),
                    protoPlayer.getName(),
                    fromProtoRole(protoPlayer.getRole())
            );
            player.setScore(protoPlayer.getScore());
            state.addPlayer(player);

            // КРИТИЧНО: Сохраняем адреса из proto (для DEPUTY)
            if (protoPlayer.hasIpAddress() && protoPlayer.hasPort() &&
                    !protoPlayer.getIpAddress().isEmpty()) {
                try {
                    InetSocketAddress addr = new InetSocketAddress(
                            protoPlayer.getIpAddress(), protoPlayer.getPort());
                    // Используем updatePlayerAddressFromProto - не перезаписывает реальные адреса
                    updatePlayerAddressFromProto(protoPlayer.getId(), addr);
                } catch (Exception e) {
                    System.err.println("[GAME] Failed to parse address: " + e.getMessage());
                }
            }
        }

        return state;
    }

    private GameConfig fromProtoConfig(SnakesProto.GameConfig protoConfig) {
        return new GameConfig(
                protoConfig.getWidth(),
                protoConfig.getHeight(),
                protoConfig.getFoodStatic(),
                protoConfig.getStateDelayMs()
        );
    }

    private SnakesProto.Direction toProtoDirection(Direction dir) {
        return switch (dir) {
            case UP -> SnakesProto.Direction.UP;
            case DOWN -> SnakesProto.Direction.DOWN;
            case LEFT -> SnakesProto.Direction.LEFT;
            case RIGHT -> SnakesProto.Direction.RIGHT;
        };
    }

    private Direction fromProtoDirection(SnakesProto.Direction dir) {
        return switch (dir) {
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
            case LEFT -> Direction.LEFT;
            case RIGHT -> Direction.RIGHT;
        };
    }

    private SnakesProto.NodeRole toProtoRole(NodeRole role) {
        return switch (role) {
            case NORMAL -> SnakesProto.NodeRole.NORMAL;
            case MASTER -> SnakesProto.NodeRole.MASTER;
            case DEPUTY -> SnakesProto.NodeRole.DEPUTY;
            case VIEWER -> SnakesProto.NodeRole.VIEWER;
        };
    }

    private NodeRole fromProtoRole(SnakesProto.NodeRole role) {
        return switch (role) {
            case NORMAL -> NodeRole.NORMAL;
            case MASTER -> NodeRole.MASTER;
            case DEPUTY -> NodeRole.DEPUTY;
            case VIEWER -> NodeRole.VIEWER;
        };
    }

    private static class PendingMessage {
        final SnakesProto.GameMessage message;
        final InetSocketAddress address;
        final long createdTime;
        long sentTime;

        PendingMessage(SnakesProto.GameMessage message, InetSocketAddress address, long sentTime) {
            this.message = message;
            this.address = address;
            this.createdTime = sentTime;
            this.sentTime = sentTime;
        }
    }

    public static class GameInfo {
        private final String name;
        private final InetSocketAddress masterAddress;
        private final GameConfig config;
        private final int playerCount;
        private final boolean canJoin;
        private final long lastSeen;

        public GameInfo(String name, InetSocketAddress masterAddress, GameConfig config,
                        int playerCount, boolean canJoin) {
            this.name = name;
            this.masterAddress = masterAddress;
            this.config = config;
            this.playerCount = playerCount;
            this.canJoin = canJoin;
            this.lastSeen = System.currentTimeMillis();
        }

        public String getName() { return name; }
        public InetSocketAddress getMasterAddress() { return masterAddress; }
        public GameConfig getConfig() { return config; }
        public int getPlayerCount() { return playerCount; }
        public boolean canJoin() { return canJoin; }
        public long getLastSeen() { return lastSeen; }
    }
}