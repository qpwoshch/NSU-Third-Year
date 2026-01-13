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

    // Для отслеживания последней активности по каждому игроку на стороне MASTER
    private final Map<Integer, Long> playerLastActivity;

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

        // ВАЖНО: Сохраняем свой адрес
        try {
            int myPort = networkManager.getLocalPort();
            java.net.InetAddress localAddr = java.net.InetAddress.getLocalHost();
            player.setAddress(new InetSocketAddress(localAddr, myPort));
            System.out.println("[GAME] My address: " + player.getAddress());
        } catch (Exception e) {
            System.err.println("[GAME] Failed to get my address: " + e.getMessage());
        }

        startGameLoop();
        startAnnouncement();
        startTimeoutChecker();

        System.out.println("[GAME] Started new game: " + gameName + " as player " + myId);
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

        System.out.println("Joining game: " + gameName + " at " + masterAddress);
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

        // Сбрасываем счётчики
        msgSeqCounter.set(0);

        System.out.println("[GAME] Client state reset");
    }

    public void leaveGame() {
        System.out.println("[GAME] Leaving game, myRole=" + myRole + ", myId=" + myId);

        // Если мы MASTER - сначала передаём роль
        if (myRole == NodeRole.MASTER && gameState != null) {
            transferMasterRoleBeforeLeave();
        }
        // Если мы не MASTER - уведомляем сервер
        else if (myRole != null && masterAddress != null && myId > 0) {
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

        // Останавливаем задачи
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

        Player newMaster = null;

        // Ищем DEPUTY
        for (Player player : gameState.getPlayers().values()) {
            if (player.getId() != myId &&
                    player.getRole() == NodeRole.DEPUTY &&
                    player.getAddress() != null) {
                newMaster = player;
                break;
            }
        }

        // Если нет DEPUTY, ищем NORMAL
        if (newMaster == null) {
            for (Player player : gameState.getPlayers().values()) {
                if (player.getId() != myId &&
                        player.getRole() == NodeRole.NORMAL &&
                        player.getAddress() != null) {
                    newMaster = player;
                    break;
                }
            }
        }

        if (newMaster != null) {
            System.out.println("[GAME] Transferring MASTER to player " + newMaster.getId());

            // Обновляем роли в gameState
            newMaster.setRole(NodeRole.MASTER);
            Player me = gameState.getPlayer(myId);
            if (me != null) {
                me.setRole(NodeRole.VIEWER);
            }

            // Отправляем ТОЛЬКО новому MASTER уведомление
            SnakesProto.GameMessage.RoleChangeMsg roleChange = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                    .setSenderRole(SnakesProto.NodeRole.VIEWER)
                    .setReceiverRole(SnakesProto.NodeRole.MASTER)
                    .build();

            networkManager.send(SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setReceiverId(newMaster.getId())
                    .setRoleChange(roleChange)
                    .build(), newMaster.getAddress());

            // Рассылаем последнее состояние с обновлёнными ролями
            broadcastState();

            System.out.println("[GAME] MASTER role transferred to " + newMaster.getId());
        } else {
            System.out.println("[GAME] No one to transfer MASTER role to");
        }
    }



    public void shutdown() {
        stopDiscovery();
        stopGame();
        scheduler.shutdownNow();
        networkManager.stop();
    }

    public void startDiscovery() {
        // Останавливаем предыдущий discovery если был
        stopDiscovery();

        // Убеждаемся что networkManager запущен
        if (!networkManager.isRunning()) {
            networkManager.start();
        }

        networkManager.startMulticastReceiver(MULTICAST_ADDRESS, MULTICAST_PORT);

        // Очищаем старый список
        availableGames.clear();

        discoveryTask = scheduler.scheduleAtFixedRate(() -> {
            // Удаляем старые игры (не получали анонс более 3 секунд)
            long now = System.currentTimeMillis();
            availableGames.entrySet().removeIf(e -> now - e.getValue().getLastSeen() > 3000);

            // Обновляем UI
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

    // ========== Private methods ==========

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
        myId = 0; // Сбрасываем ID
        masterAddress = null;
        deputyAddress = null;
        playerLastActivity.clear();
        processedJoins.clear();
        pendingMoves.clear();
        unackedMessages.clear();

        // НЕ останавливаем networkManager здесь!
    }

    private void gameTick() {
        if (myRole != NodeRole.MASTER || gameState == null) {
            System.out.println("[GAME] gameTick called but I am not MASTER anymore → exiting");
            return;
        }

        try {
            List<Integer> deadPlayers = gameLogic.tick(gameState, pendingMoves);
            pendingMoves.clear();

            boolean iDied = false;

            // Обрабатываем смерти
            for (int playerId : deadPlayers) {
                Player player = gameState.getPlayer(playerId);
                if (player != null) {
                    NodeRole oldRole = player.getRole();
                    player.setRole(NodeRole.VIEWER);

                    System.out.println("[GAME] Player " + playerId + " died, was " + oldRole + ", now VIEWER");

                    if (oldRole == NodeRole.DEPUTY) {
                        deputyAddress = null;
                    }

                    // Уведомляем игрока (кроме себя)
                    if (player.getAddress() != null && playerId != myId) {
                        SnakesProto.GameMessage.RoleChangeMsg roleChangeMsg = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                                .setReceiverRole(SnakesProto.NodeRole.VIEWER)
                                .build();

                        sendMessage(player.getAddress(), SnakesProto.GameMessage.newBuilder()
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

            // Если я умер - сначала рассылаем состояние, потом передаём роль
            if (iDied) {
                System.out.println("[GAME] I (MASTER) died!");

                // Обновляем свою роль
                Player me = gameState.getPlayer(myId);
                if (me != null) {
                    me.setRole(NodeRole.VIEWER);
                }

                ensureDeputy();
                broadcastState(); // Рассылаем состояние пока ещё MASTER

                // Теперь передаём роль
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
        System.out.println("[GAME] Current players:");
        for (Player p : gameState.getPlayers().values()) {
            System.out.println("[GAME]   ID=" + p.getId() + ", role=" + p.getRole() + ", addr=" + p.getAddress());
        }

        Player newMaster = null;

        // Ищем DEPUTY
        for (Player player : gameState.getPlayers().values()) {
            if (player.getId() != myId &&
                    player.getRole() == NodeRole.DEPUTY &&
                    player.getAddress() != null) {
                newMaster = player;
                System.out.println("[GAME] Found DEPUTY: " + player.getId());
                break;
            }
        }

        // Если нет DEPUTY, ищем NORMAL
        if (newMaster == null) {
            for (Player player : gameState.getPlayers().values()) {
                if (player.getId() != myId &&
                        player.getRole() == NodeRole.NORMAL &&
                        player.getAddress() != null) {
                    newMaster = player;
                    System.out.println("[GAME] Found NORMAL: " + player.getId());
                    break;
                }
            }
        }

        if (newMaster != null) {
            System.out.println("[GAME] Promoting player " + newMaster.getId() + " at " + newMaster.getAddress());

            // Обновляем роли
            newMaster.setRole(NodeRole.MASTER);

            Player me = gameState.getPlayer(myId);
            if (me != null) {
                me.setRole(NodeRole.VIEWER);
                System.out.println("[GAME] My address in gameState: " + me.getAddress());
            }
            myRole = NodeRole.VIEWER;

            // Сбрасываем других DEPUTY
            for (Player player : gameState.getPlayers().values()) {
                if (player.getId() != myId && player.getId() != newMaster.getId() &&
                        player.getRole() == NodeRole.DEPUTY) {
                    player.setRole(NodeRole.NORMAL);
                }
            }

            // Отправляем новому MASTER
            SnakesProto.GameMessage.RoleChangeMsg roleChange = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                    .setSenderRole(SnakesProto.NodeRole.VIEWER)
                    .setReceiverRole(SnakesProto.NodeRole.MASTER)
                    .build();

            sendMessage(newMaster.getAddress(), SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setReceiverId(newMaster.getId())
                    .setRoleChange(roleChange)
                    .build());

            // Рассылаем состояние
            System.out.println("[GAME] Broadcasting final state before stepping down...");
            broadcastState();

            masterAddress = newMaster.getAddress();
            deputyAddress = null;
            lastMasterActivity = System.currentTimeMillis();

            // Останавливаем задачи
            if (gameLoopTask != null) {
                gameLoopTask.cancel(false);
                gameLoopTask = null;
            }
            if (announcementTask != null) {
                announcementTask.cancel(false);
                announcementTask = null;
            }

            System.out.println("[GAME] I am now VIEWER, masterAddress=" + masterAddress);

            if (stateUpdateCallback != null) {
                stateUpdateCallback.accept(gameState);
            }
        } else {
            System.out.println("[GAME] No one to promote!");
        }
        SnakesProto.GameMessage.JoinMsg joinAsViewer = SnakesProto.GameMessage.JoinMsg.newBuilder()
                .setPlayerName(myName)
                .setGameName(gameName)
                .setRequestedRole(SnakesProto.NodeRole.VIEWER)
                .build();

        SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeqCounter.getAndIncrement())
                .setJoin(joinAsViewer)
                .setSenderId(myId)
                .build();

        sendMessage(masterAddress, msg);

        System.out.println("[GAME] Ex-master sent JoinMsg as VIEWER to new master " + newMaster.getId());
    }

    private void ensureDeputy() {
        if (myRole != NodeRole.MASTER || gameState == null) return;

        boolean hasDeputy = gameState.getPlayers().values().stream()
                .anyMatch(p -> p.getRole() == NodeRole.DEPUTY && p.getId() != myId);

        if (!hasDeputy) {
            for (Player player : gameState.getPlayers().values()) {
                if (player.getRole() == NodeRole.NORMAL && player.getId() != myId && player.getAddress() != null) {
                    player.setRole(NodeRole.DEPUTY);
                    deputyAddress = player.getAddress();

                    SnakesProto.GameMessage.RoleChangeMsg roleChange = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                            .setReceiverRole(SnakesProto.NodeRole.DEPUTY)
                            .build();

                    sendMessage(player.getAddress(), SnakesProto.GameMessage.newBuilder()
                            .setMsgSeq(msgSeqCounter.getAndIncrement())
                            .setSenderId(myId)
                            .setReceiverId(player.getId())
                            .setRoleChange(roleChange)
                            .build());

                    System.out.println("Assigned DEPUTY role to player " + player.getId());
                    break;
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

        for (Player player : gameState.getPlayers().values()) {
            if (player.getId() == myId) {
                System.out.println("[GAME]   Skip myself (ID=" + myId + ")");
                continue;
            }
            if (player.getAddress() == null) {
                System.out.println("[GAME]   Skip player " + player.getId() + " - no address");
                continue;
            }

            System.out.println("[GAME]   Sending to player " + player.getId() + " at " + player.getAddress());

            sendMessage(player.getAddress(), SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setReceiverId(player.getId())
                    .setState(stateMsg)
                    .build());
        }
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

            // Отправляем и multicast и broadcast для надёжности
            networkManager.sendMulticast(msg, MULTICAST_ADDRESS, MULTICAST_PORT);
            networkManager.sendBroadcast(msg, MULTICAST_PORT);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendMessage(InetSocketAddress address, SnakesProto.GameMessage msg) {
        if (address == null) return;

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

        // Переотправка неподтверждённых сообщений
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

        // Проверка timeout-ов игроков (только для MASTER)
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

        // Проверка timeout-а MASTER-а (для DEPUTY)
        if (myRole == NodeRole.DEPUTY && lastMasterActivity > 0) {
            if (now - lastMasterActivity > nodeTimeout * 3) {
                System.out.println("[GAME] MASTER timeout detected, promoting myself to MASTER");
                promoteToMaster();
            }
        }

        // Проверка timeout-а MASTER-а (для NORMAL)
        // УБИРАЕМ бесконечное переключение - просто ждём нового MASTER
        if (myRole == NodeRole.NORMAL && lastMasterActivity > 0) {
            if (now - lastMasterActivity > nodeTimeout * 5) {
                // Только логируем, не переключаемся бесконечно
                // Новый MASTER должен прислать нам RoleChangeMsg или StateMsg
                System.out.println("[GAME] NORMAL: MASTER timeout, waiting for new MASTER...");
                // Сбрасываем таймер чтобы не спамить
                lastMasterActivity = now;
            }
        }
    }

    private void handlePlayerTimeout(int playerId) {
        Player player = gameState.getPlayer(playerId);
        if (player == null) return;

        System.out.println("[GAME] Player " + playerId + " timed out");

        // Удаляем из processedJoins
        if (player.getAddress() != null) {
            processedJoins.remove(player.getAddress());
        }

        if (player.getRole() == NodeRole.DEPUTY) {
            deputyAddress = null;
        }

        Snake snake = gameState.getSnake(playerId);
        if (snake != null && snake.getState() == Snake.SnakeState.ALIVE) {
            snake.setState(Snake.SnakeState.ZOMBIE);
            System.out.println("[GAME] Snake of player " + playerId + " became ZOMBIE");
        }

        player.setRole(NodeRole.VIEWER);
        playerLastActivity.remove(playerId);

        ensureDeputy();
    }

    private void promoteToMaster() {
        System.out.println("Promoting to MASTER");
        myRole = NodeRole.MASTER;

        if (gameState != null) {
            for (Player player : gameState.getPlayers().values()) {
                if (player.getId() == myId) continue;
                if (player.getAddress() == null) continue;

                SnakesProto.GameMessage.RoleChangeMsg.Builder roleChangeBuilder =
                        SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                                .setSenderRole(SnakesProto.NodeRole.MASTER);

                sendMessage(player.getAddress(), SnakesProto.GameMessage.newBuilder()
                        .setMsgSeq(msgSeqCounter.getAndIncrement())
                        .setSenderId(myId)
                        .setReceiverId(player.getId())
                        .setRoleChange(roleChangeBuilder.build())
                        .build());
            }
        }

        startGameLoop();
        startAnnouncement();
        ensureDeputy();
    }

    private void updatePlayerActivity(int playerId) {
        playerLastActivity.put(playerId, System.currentTimeMillis());

        if (gameState != null) {
            Player player = gameState.getPlayer(playerId);
            if (player != null) {
                player.updateActivity();
            }
        }
    }

    private void handleMessage(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        try {
            // Обновляем активность отправителя
            if (msg.hasSenderId()) {
                updatePlayerActivity(msg.getSenderId());
            }

            if (msg.hasAck()) {
                handleAck(msg);
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

    private void handleAck(SnakesProto.GameMessage msg) {
        unackedMessages.remove(msg.getMsgSeq());

        // Если это ответ на JoinMsg - сохраняем свой ID (только если ещё не установлен)
        if (msg.hasReceiverId() && msg.getReceiverId() > 0 && myId <= 0) {
            myId = msg.getReceiverId();
            System.out.println("[GAME] Received my ID: " + myId);
        }

        if (msg.hasSenderId()) {
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

            System.out.println("Found game: " + ann.getGameName() + " at " + sender);
        }

        if (gamesListCallback != null) {
            gamesListCallback.accept(new ArrayList<>(availableGames.values()));
        }
    }

    private void handleJoin(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        if (myRole != NodeRole.MASTER) return;

        SnakesProto.GameMessage.JoinMsg join = msg.getJoin();

        System.out.println("[GAME] Join request from " + sender + ", name: " + join.getPlayerName());

        // Проверяем существующего игрока с этого адреса
        Integer existingPlayerId = processedJoins.get(sender);
        if (existingPlayerId != null) {
            Player existingPlayer = gameState.getPlayer(existingPlayerId);

            if (existingPlayer != null) {
                // Если игрок активен (не VIEWER) - просто переотправляем ACK
                if (existingPlayer.getRole() != NodeRole.VIEWER) {
                    System.out.println("[GAME] Player " + existingPlayerId + " already active, resending ACK");
                    sendAck(sender, msg.getMsgSeq(), myId, existingPlayerId);
                    return;
                }

                // Если игрок был VIEWER - удаляем его и создаём нового
                System.out.println("[GAME] Player " + existingPlayerId + " was VIEWER, removing for rejoin");
                gameState.removePlayer(existingPlayerId);
                playerLastActivity.remove(existingPlayerId);
                processedJoins.remove(sender);
            } else {
                // Игрока нет в состоянии - очищаем запись
                processedJoins.remove(sender);
            }
        }

        // Также проверяем всех игроков по адресу (на случай если адрес изменился в processedJoins)
        List<Integer> toRemove = new ArrayList<>();
        for (Player existingPlayer : gameState.getPlayers().values()) {
            if (existingPlayer.getAddress() != null && existingPlayer.getAddress().equals(sender)) {
                if (existingPlayer.getRole() != NodeRole.VIEWER) {
                    // Активный игрок - отправляем ACK
                    System.out.println("[GAME] Found active player " + existingPlayer.getId() + " at same address");
                    processedJoins.put(sender, existingPlayer.getId());
                    sendAck(sender, msg.getMsgSeq(), myId, existingPlayer.getId());
                    return;
                } else {
                    // VIEWER - помечаем на удаление
                    toRemove.add(existingPlayer.getId());
                }
            }
        }

        // Удаляем старых VIEWER с этого адреса
        for (int id : toRemove) {
            System.out.println("[GAME] Removing old VIEWER " + id + " for rejoin");
            gameState.removePlayer(id);
            playerLastActivity.remove(id);
        }

        // Создаём нового игрока
        if (join.getRequestedRole() == SnakesProto.NodeRole.NORMAL ||
                join.getRequestedRole() == SnakesProto.NodeRole.VIEWER) {

            int newId = playerIdCounter.getAndIncrement();
            NodeRole role = fromProtoRole(join.getRequestedRole());

            Player newPlayer = new Player(newId, join.getPlayerName(), role);
            newPlayer.setAddress(sender);
            gameState.addPlayer(newPlayer);
            playerLastActivity.put(newId, System.currentTimeMillis());
            processedJoins.put(sender, newId);

            System.out.println("[GAME] Created new player " + newId + " (" + role + ") at " + sender);

            if (role != NodeRole.VIEWER) {
                Snake snake = gameLogic.createSnakeForPlayer(gameState, newId);
                if (snake == null) {
                    gameState.removePlayer(newId);
                    playerLastActivity.remove(newId);
                    processedJoins.remove(sender);
                    sendError(sender, "No room for new snake", msg.getMsgSeq());
                    return;
                }
                gameState.addSnake(snake);
                System.out.println("[GAME] Created snake for player " + newId);
            }

            sendAck(sender, msg.getMsgSeq(), myId, newId);

            // Отправляем состояние
            SnakesProto.GameMessage.StateMsg stateMsg = SnakesProto.GameMessage.StateMsg.newBuilder()
                    .setState(buildProtoState())
                    .build();

            sendMessage(sender, SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setReceiverId(newId)
                    .setState(stateMsg)
                    .build());

            System.out.println("[GAME] Player " + newId + " joined successfully");

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
        System.out.println("[GAME] handleState called, myRole=" + myRole);
        if (myRole == NodeRole.MASTER) {
            System.out.println("[GAME] Ignoring state - I am MASTER");
            sendAck(sender, msg.getMsgSeq(), myId, msg.getSenderId());
            return;
        }

        lastMasterActivity = System.currentTimeMillis();

        SnakesProto.GameState protoState = msg.getState().getState();

        // Игнорируем старые состояния
        if (gameState != null && protoState.getStateOrder() <= gameState.getStateOrder()) {
            sendAck(sender, msg.getMsgSeq(), myId, msg.getSenderId());
            return;
        }

        System.out.println("[GAME] Received state #" + protoState.getStateOrder() + " from " + sender);

        // Парсим новое состояние
        gameState = fromProtoState(protoState, config != null ? config : GameConfig.defaultConfig());
        masterAddress = sender;

        // Синхронизируем свою роль с состоянием (но доверяем локальной если мы VIEWER после смерти)
        if (myId > 0) {
            Player me = gameState.getPlayer(myId);
            if (me != null) {
                // Если в состоянии другая роль - обновляем
                if (myRole != me.getRole()) {
                    System.out.println("[GAME] Syncing role from state: " + myRole + " -> " + me.getRole());
                    myRole = me.getRole();
                }
            }
        }

        // Находим DEPUTY
        deputyAddress = null;
        for (Player p : gameState.getPlayers().values()) {
            if (p.getRole() == NodeRole.DEPUTY && p.getAddress() != null && p.getId() != myId) {
                deputyAddress = p.getAddress();
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
                ", receiverRole=" + (roleChange.hasReceiverRole() ? roleChange.getReceiverRole() : "none") +
                ", senderId=" + (msg.hasSenderId() ? msg.getSenderId() : "none") +
                ", receiverId=" + (msg.hasReceiverId() ? msg.getReceiverId() : "none"));

        // Проверяем что сообщение адресовано нам
        if (msg.hasReceiverId() && msg.getReceiverId() != myId) {
            System.out.println("[GAME] RoleChange not for me (I am " + myId + "), ignoring");
            if (msg.hasSenderId()) {
                sendAck(sender, msg.getMsgSeq(), myId, msg.getSenderId());
            }
            return;
        }

        // Обновляем адрес отправителя
        if (msg.hasSenderId() && gameState != null) {
            Player senderPlayer = gameState.getPlayer(msg.getSenderId());
            if (senderPlayer != null) {
                senderPlayer.setAddress(sender);
                playerLastActivity.put(msg.getSenderId(), System.currentTimeMillis());
            }
        }

        boolean needsUiUpdate = false;

        // Обработка изменения МОЕЙ роли
        if (roleChange.hasReceiverRole()) {
            NodeRole newRole = fromProtoRole(roleChange.getReceiverRole());
            NodeRole oldRole = myRole;

            System.out.println("[GAME] My role changing: " + oldRole + " -> " + newRole);
            myRole = newRole;
            needsUiUpdate = true;

            if (newRole == NodeRole.MASTER && oldRole != NodeRole.MASTER) {
                System.out.println("[GAME] === I AM NOW THE MASTER ===");

                if (gameState != null) {
                    // Обновляем свою роль
                    Player me = gameState.getPlayer(myId);
                    if (me != null) {
                        me.setRole(NodeRole.MASTER);

                        // Сохраняем свой адрес если его нет
                        if (me.getAddress() == null) {
                            try {
                                int myPort = networkManager.getLocalPort();
                                java.net.InetAddress localAddr = java.net.InetAddress.getLocalHost();
                                me.setAddress(new InetSocketAddress(localAddr, myPort));
                                System.out.println("[GAME] Set my address: " + me.getAddress());
                            } catch (Exception e) {
                                System.err.println("[GAME] Failed to set my address");
                            }
                        }
                    }

                    // Старый MASTER -> VIEWER
                    if (msg.hasSenderId() && msg.getSenderId() != myId) {
                        Player oldMaster = gameState.getPlayer(msg.getSenderId());
                        if (oldMaster != null) {
                            oldMaster.setRole(NodeRole.VIEWER);
                            System.out.println("[GAME] Old MASTER " + msg.getSenderId() + " -> VIEWER");
                        }
                    }

                    // Все другие DEPUTY -> NORMAL (только один MASTER)
                    for (Player player : gameState.getPlayers().values()) {
                        if (player.getId() != myId && player.getRole() == NodeRole.DEPUTY) {
                            player.setRole(NodeRole.NORMAL);
                            System.out.println("[GAME] Reset DEPUTY " + player.getId() + " -> NORMAL");
                        }
                    }
                }

                masterAddress = null;
                deputyAddress = null;

                startGameLoop();
                startAnnouncement();
                ensureDeputy();

                // Рассылаем обновлённое состояние
                if (gameState != null) {
                    broadcastState();
                }

            } else if (newRole == NodeRole.DEPUTY) {
                System.out.println("[GAME] I am now DEPUTY");
                if (gameState != null) {
                    Player me = gameState.getPlayer(myId);
                    if (me != null) {
                        me.setRole(NodeRole.DEPUTY);
                    }
                }

            } else if (newRole == NodeRole.VIEWER) {
                System.out.println("[GAME] I am now VIEWER");
                if (gameState != null) {
                    Player me = gameState.getPlayer(myId);
                    if (me != null) {
                        me.setRole(NodeRole.VIEWER);
                    }
                }
            }
        }

        // Обработка роли отправителя
        if (roleChange.hasSenderRole()) {
            SnakesProto.NodeRole senderRole = roleChange.getSenderRole();

            if (senderRole == SnakesProto.NodeRole.MASTER) {
                masterAddress = sender;
                lastMasterActivity = System.currentTimeMillis();
                System.out.println("[GAME] MASTER address: " + sender);

                if (msg.hasSenderId() && gameState != null) {
                    Player masterPlayer = gameState.getPlayer(msg.getSenderId());
                    if (masterPlayer != null) {
                        masterPlayer.setRole(NodeRole.MASTER);
                    }
                }
                needsUiUpdate = true;

            } else if (senderRole == SnakesProto.NodeRole.VIEWER) {
                // Кто-то выходит
                if (myRole == NodeRole.MASTER && msg.hasSenderId()) {
                    handlePlayerLeave(msg.getSenderId());
                    needsUiUpdate = true;
                }
            }
        }

        // Обновляем UI
        if (needsUiUpdate && stateUpdateCallback != null && gameState != null) {
            stateUpdateCallback.accept(gameState);
        }

        if (msg.hasSenderId()) {
            sendAck(sender, msg.getMsgSeq(), myId, msg.getSenderId());
        }
    }

    private void handleError(SnakesProto.GameMessage msg) {
        System.err.println("Error from server: " + msg.getError().getErrorMessage());
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

        // Удаляем из processedJoins
        if (player.getAddress() != null) {
            processedJoins.remove(player.getAddress());
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

    // ========== Proto conversion methods ==========

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

            if (player.getAddress() != null) {
                playerBuilder.setIpAddress(player.getAddress().getAddress().getHostAddress());
                playerBuilder.setPort(player.getAddress().getPort());
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
                SnakesProto.GamePlayer.Builder playerBuilder = SnakesProto.GamePlayer.newBuilder()
                        .setId(player.getId())
                        .setName(player.getName())
                        .setRole(toProtoRole(player.getRole()))
                        .setScore(player.getScore());

                playersBuilder.addPlayers(playerBuilder.build());
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

            if (protoPlayer.hasIpAddress() && protoPlayer.hasPort()) {
                try {
                    player.setAddress(new InetSocketAddress(
                            protoPlayer.getIpAddress(), protoPlayer.getPort()));
                } catch (Exception ignored) {
                }
            }

            state.addPlayer(player);
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

    // ========== Helper classes ==========

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

        public String getName() {
            return name;
        }

        public InetSocketAddress getMasterAddress() {
            return masterAddress;
        }

        public GameConfig getConfig() {
            return config;
        }

        public int getPlayerCount() {
            return playerCount;
        }

        public boolean canJoin() {
            return canJoin;
        }

        public long getLastSeen() {
            return lastSeen;
        }
    }
}