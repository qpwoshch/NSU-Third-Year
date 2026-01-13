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

        networkManager.start();
        startGameLoop();
        startAnnouncement();
        startTimeoutChecker();

        System.out.println("Started new game: " + gameName + " as player " + myId);
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

    public void leaveGame() {
        if (myRole != null && myRole != NodeRole.MASTER && masterAddress != null) {
            SnakesProto.GameMessage.RoleChangeMsg roleChange = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                    .setSenderRole(SnakesProto.NodeRole.VIEWER)
                    .build();

            sendMessage(masterAddress, SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setRoleChange(roleChange)
                    .build());
        }

        stopGame();
    }

    public void shutdown() {
        leaveGame();
        scheduler.shutdownNow();
        networkManager.stop();
    }

    public void startDiscovery() {
        networkManager.start();
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

        System.out.println("Discovery started");
    }

    public void stopDiscovery() {
        if (discoveryTask != null) {
            discoveryTask.cancel(false);
            discoveryTask = null;
        }
        networkManager.stopMulticastReceiver();
        System.out.println("Discovery stopped");
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
        playerLastActivity.clear();
    }

    private void gameTick() {
        if (myRole != NodeRole.MASTER || gameState == null) return;

        try {
            // tick теперь возвращает список погибших
            List<Integer> deadPlayers = gameLogic.tick(gameState, pendingMoves);
            pendingMoves.clear();

            // Обрабатываем смерти игроков
            for (int playerId : deadPlayers) {
                Player player = gameState.getPlayer(playerId);
                if (player != null) {
                    NodeRole oldRole = player.getRole();
                    player.setRole(NodeRole.VIEWER);

                    System.out.println("[GAME] Player " + playerId + " died, was " + oldRole + ", now VIEWER");

                    // Если умер DEPUTY - нужен новый
                    if (oldRole == NodeRole.DEPUTY) {
                        deputyAddress = null;
                    }

                    // Уведомляем игрока что он теперь VIEWER
                    if (player.getAddress() != null && playerId != myId) {
                        SnakesProto.GameMessage.RoleChangeMsg roleChange = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                                .setReceiverRole(SnakesProto.NodeRole.VIEWER)
                                .build();

                        sendMessage(player.getAddress(), SnakesProto.GameMessage.newBuilder()
                                .setMsgSeq(msgSeqCounter.getAndIncrement())
                                .setSenderId(myId)
                                .setReceiverId(playerId)
                                .setRoleChange(roleChange)
                                .build());
                    }

                    // Если умер сам MASTER
                    if (playerId == myId) {
                        myRole = NodeRole.VIEWER;
                        System.out.println("[GAME] I (MASTER) died, becoming VIEWER but staying in game");

                        // Нужно передать роль MASTER кому-то другому
                        promoteNewMaster();
                    }
                }
            }

            ensureDeputy();
            broadcastState();

            if (stateUpdateCallback != null) {
                stateUpdateCallback.accept(gameState);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void promoteNewMaster() {
        if (gameState == null) return;

        // Ищем DEPUTY или NORMAL чтобы сделать новым MASTER
        Player newMaster = null;

        // Сначала ищем DEPUTY
        for (Player player : gameState.getPlayers().values()) {
            if (player.getRole() == NodeRole.DEPUTY && player.getAddress() != null) {
                newMaster = player;
                break;
            }
        }

        // Если нет DEPUTY, ищем NORMAL
        if (newMaster == null) {
            for (Player player : gameState.getPlayers().values()) {
                if (player.getRole() == NodeRole.NORMAL && player.getAddress() != null) {
                    newMaster = player;
                    break;
                }
            }
        }

        if (newMaster != null) {
            System.out.println("[GAME] Promoting player " + newMaster.getId() + " to MASTER");

            newMaster.setRole(NodeRole.MASTER);

            // Уведомляем нового MASTER
            SnakesProto.GameMessage.RoleChangeMsg roleChange = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                    .setReceiverRole(SnakesProto.NodeRole.MASTER)
                    .build();

            sendMessage(newMaster.getAddress(), SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setReceiverId(newMaster.getId())
                    .setRoleChange(roleChange)
                    .build());

            // Обновляем masterAddress для себя
            masterAddress = newMaster.getAddress();
            deputyAddress = null;

            // Останавливаем свои MASTER-задачи
            if (gameLoopTask != null) {
                gameLoopTask.cancel(false);
                gameLoopTask = null;
            }
            if (announcementTask != null) {
                announcementTask.cancel(false);
                announcementTask = null;
            }
        } else {
            System.out.println("[GAME] No one to promote to MASTER, game ends");
        }
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

        for (Player player : gameState.getPlayers().values()) {
            if (player.getId() == myId) continue;
            if (player.getAddress() == null) continue;

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
        // Увеличиваем timeout чтобы не было ложных срабатываний
        long nodeTimeout = (long) (config.getStateDelayMs() * 0.8);

        // Переотправка неподтверждённых сообщений
        Iterator<Map.Entry<Long, PendingMessage>> it = unackedMessages.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, PendingMessage> entry = it.next();
            PendingMessage pm = entry.getValue();

            // Удаляем слишком старые сообщения (больше 5 секунд)
            if (now - pm.createdTime > 5000) {
                it.remove();
                continue;
            }

            if (now - pm.sentTime > resendInterval) {
                InetSocketAddress target = pm.address;

                // При падении MASTER переадресуем на DEPUTY
                if (myRole == NodeRole.NORMAL && masterAddress != null &&
                        pm.address.equals(masterAddress) && deputyAddress != null) {
                    if (now - lastMasterActivity > nodeTimeout) {
                        target = deputyAddress;
                        masterAddress = deputyAddress;
                        System.out.println("Switching to DEPUTY: " + deputyAddress);
                    }
                }

                networkManager.send(pm.message, target);
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
                    // Если нет записи, создаём
                    playerLastActivity.put(player.getId(), now);
                    continue;
                }

                if (now - lastActivity > nodeTimeout * 3) { // Увеличиваем множитель для надёжности
                    timedOut.add(player.getId());
                }
            }

            for (int playerId : timedOut) {
                handlePlayerTimeout(playerId);
            }
        }

        // Проверка timeout-а MASTER-а (для DEPUTY)
        if (myRole == NodeRole.DEPUTY && lastMasterActivity > 0) {
            if (now - lastMasterActivity > nodeTimeout * 2) {
                promoteToMaster();
            }
        }

        // Проверка timeout-а MASTER-а (для NORMAL)
        if (myRole == NodeRole.NORMAL && lastMasterActivity > 0) {
            if (now - lastMasterActivity > nodeTimeout * 2 && deputyAddress != null) {
                masterAddress = deputyAddress;
                lastMasterActivity = now;
                System.out.println("NORMAL: Switching to DEPUTY");
            }
        }
    }

    private void handlePlayerTimeout(int playerId) {
        Player player = gameState.getPlayer(playerId);
        if (player == null) return;

        System.out.println("Player " + playerId + " timed out");

        if (player.getRole() == NodeRole.DEPUTY) {
            deputyAddress = null;
        }

        Snake snake = gameState.getSnake(playerId);
        if (snake != null && snake.getState() == Snake.SnakeState.ALIVE) {
            snake.setState(Snake.SnakeState.ZOMBIE);
            System.out.println("Snake of player " + playerId + " became ZOMBIE");
        }

        player.setRole(NodeRole.VIEWER);
        playerLastActivity.remove(playerId);

        // Если был DEPUTY, назначаем нового
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

        // Если это ответ на JoinMsg - сохраняем свой ID
        if (msg.hasReceiverId() && myId == 0) {
            myId = msg.getReceiverId();
            System.out.println("Received my ID: " + myId);
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

        System.out.println("Player joining: " + join.getPlayerName() + " from " + sender);

        if (join.getRequestedRole() == SnakesProto.NodeRole.NORMAL ||
                join.getRequestedRole() == SnakesProto.NodeRole.VIEWER) {

            int newId = playerIdCounter.getAndIncrement();
            NodeRole role = fromProtoRole(join.getRequestedRole());

            Player newPlayer = new Player(newId, join.getPlayerName(), role);
            newPlayer.setAddress(sender);
            gameState.addPlayer(newPlayer);
            playerLastActivity.put(newId, System.currentTimeMillis());

            if (role != NodeRole.VIEWER) {
                Snake snake = gameLogic.createSnakeForPlayer(gameState, newId);
                if (snake == null) {
                    gameState.removePlayer(newId);
                    playerLastActivity.remove(newId);
                    sendError(sender, "No room for new snake", msg.getMsgSeq());
                    return;
                }
                gameState.addSnake(snake);
            }

            // Отправляем ACK с ID игрока
            sendAck(sender, msg.getMsgSeq(), myId, newId);

            // Сразу отправляем текущее состояние
            SnakesProto.GameMessage.StateMsg stateMsg = SnakesProto.GameMessage.StateMsg.newBuilder()
                    .setState(buildProtoState())
                    .build();

            sendMessage(sender, SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeqCounter.getAndIncrement())
                    .setSenderId(myId)
                    .setReceiverId(newId)
                    .setState(stateMsg)
                    .build());

            System.out.println("Player " + newId + " joined successfully");

            // Проверяем нужен ли DEPUTY
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
        if (myRole == NodeRole.MASTER) return;

        lastMasterActivity = System.currentTimeMillis();

        SnakesProto.GameState protoState = msg.getState().getState();

        if (gameState != null && protoState.getStateOrder() <= gameState.getStateOrder()) {
            sendAck(sender, msg.getMsgSeq(), myId, msg.getSenderId());
            return;
        }

        // Обновляем конфиг если нужно
        if (config == null) {
            // Пытаемся получить конфиг из состояния
        }

        gameState = fromProtoState(protoState, config != null ? config : GameConfig.defaultConfig());
        masterAddress = sender;

        // Находим DEPUTY из состояния
        for (Player p : gameState.getPlayers().values()) {
            if (p.getRole() == NodeRole.DEPUTY && p.getAddress() != null) {
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

        if (roleChange.hasReceiverRole()) {
            NodeRole newRole = fromProtoRole(roleChange.getReceiverRole());
            NodeRole oldRole = myRole;
            myRole = newRole;

            System.out.println("[GAME] My role changed: " + oldRole + " -> " + newRole);

            if (newRole == NodeRole.MASTER && oldRole != NodeRole.MASTER) {
                // Становимся MASTER
                System.out.println("[GAME] I am now the MASTER!");

                // Обновляем свою роль в gameState
                if (gameState != null) {
                    Player me = gameState.getPlayer(myId);
                    if (me != null) {
                        me.setRole(NodeRole.MASTER);
                    }
                }

                startGameLoop();
                startAnnouncement();
                ensureDeputy();
            } else if (newRole == NodeRole.VIEWER) {
                System.out.println("[GAME] I am now a VIEWER (my snake died)");
                // Остаёмся в игре как наблюдатель
            } else if (newRole == NodeRole.DEPUTY) {
                System.out.println("[GAME] I am now the DEPUTY");
            }
        }

        if (roleChange.hasSenderRole()) {
            SnakesProto.NodeRole senderRole = roleChange.getSenderRole();

            if (senderRole == SnakesProto.NodeRole.MASTER) {
                masterAddress = sender;
                System.out.println("[GAME] New MASTER address: " + sender);
            } else if (senderRole == SnakesProto.NodeRole.VIEWER) {
                // Отправитель выходит из игры
                if (myRole == NodeRole.MASTER && msg.hasSenderId()) {
                    handlePlayerLeave(msg.getSenderId());
                }
            }
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

        System.out.println("Player " + playerId + " leaving");

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