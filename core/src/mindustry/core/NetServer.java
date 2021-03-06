package mindustry.core;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.ArcAnnotate.*;
import arc.util.*;
import arc.util.CommandHandler.*;
import arc.util.io.*;
import arc.util.serialization.*;
import mindustry.annotations.Annotations.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.net.Administration.*;
import mindustry.net.Packets.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.zip.*;

import static arc.util.Log.*;
import static mindustry.Vars.*;

public class NetServer implements ApplicationListener{
    private final static int maxSnapshotSize = 430, timerBlockSync = 0;
    private final static float serverSyncTime = 12, blockSyncTime = 60 * 8;
    private final static FloatBuffer fbuffer = FloatBuffer.allocate(20);
    private final static Vec2 vector = new Vec2();
    private final static Rect viewport = new Rect();
    /** If a player goes away of their server-side coordinates by this distance, they get teleported back. */
    private final static float correctDist = 16f;

    public final Administration admins = new Administration();
    public final CommandHandler clientCommands = new CommandHandler("/");
    public TeamAssigner assigner = (player, players) -> {
        if(state.rules.pvp){
            //find team with minimum amount of players and auto-assign player to that.
            TeamData re = state.teams.getActive().min(data -> {
                if((state.rules.waveTeam == data.team && state.rules.waves) || !data.team.active()) return Integer.MAX_VALUE;

                int count = 0;
                for(Playerc other : players){
                    if(other.team() == data.team && other != player){
                        count++;
                    }
                }
                return count;
            });
            return re == null ? null : re.team;
        }

        return state.rules.defaultTeam;
    };

    private boolean closing = false;
    private Interval timer = new Interval();

    private ReusableByteOutStream writeBuffer = new ReusableByteOutStream(127);
    private Writes outputBuffer = new Writes(new DataOutputStream(writeBuffer));

    /** Stream for writing player sync data to. */
    private ReusableByteOutStream syncStream = new ReusableByteOutStream();
    /** Data stream for writing player sync data to. */
    private DataOutputStream dataStream = new DataOutputStream(syncStream);
    /** Packet handlers for custom types of messages. */
    private ObjectMap<String, Array<Cons2<Playerc, String>>> customPacketHandlers = new ObjectMap<>();

    public NetServer(){

        net.handleServer(Connect.class, (con, connect) -> {
            if(admins.isIPBanned(connect.addressTCP) || admins.isSubnetBanned(connect.addressTCP)){
                con.kick(KickReason.banned);
            }
        });

        net.handleServer(Disconnect.class, (con, packet) -> {
            if(con.player != null){
                onDisconnect(con.player, packet.reason);
            }
        });

        net.handleServer(ConnectPacket.class, (con, packet) -> {
            if(con.address.startsWith("steam:")){
                packet.uuid = con.address.substring("steam:".length());
            }

            String uuid = packet.uuid;
            byte[] buuid = Base64Coder.decode(uuid);
            CRC32 crc = new CRC32();
            crc.update(buuid, 0, 8);
            ByteBuffer buff = ByteBuffer.allocate(8);
            buff.put(buuid, 8, 8);
            buff.position(0);
            if(crc.getValue() != buff.getLong()){
                con.kick(KickReason.clientOutdated);
                return;
            }

            if(admins.isIPBanned(con.address) || admins.isSubnetBanned(con.address)) return;

            if(con.hasBegunConnecting){
                con.kick(KickReason.idInUse);
                return;
            }

            PlayerInfo info = admins.getInfo(uuid);

            con.hasBegunConnecting = true;
            con.mobile = packet.mobile;

            if(packet.uuid == null || packet.usid == null){
                con.kick(KickReason.idInUse);
                return;
            }

            if(admins.isIDBanned(uuid)){
                con.kick(KickReason.banned);
                return;
            }

            if(Time.millis() < info.lastKicked){
                con.kick(KickReason.recentKick);
                return;
            }

            if(admins.getPlayerLimit() > 0 && Groups.player.size() >= admins.getPlayerLimit() && !netServer.admins.isAdmin(uuid, packet.usid)){
                con.kick(KickReason.playerLimit);
                return;
            }

            Array<String> extraMods = packet.mods.copy();
            Array<String> missingMods = mods.getIncompatibility(extraMods);

            if(!extraMods.isEmpty() || !missingMods.isEmpty()){
                //can't easily be localized since kick reasons can't have formatted text with them
                StringBuilder result = new StringBuilder("[accent]Incompatible mods![]\n\n");
                if(!missingMods.isEmpty()){
                    result.append("Missing:[lightgray]\n").append("> ").append(missingMods.toString("\n> "));
                    result.append("[]\n");
                }

                if(!extraMods.isEmpty()){
                    result.append("Unnecessary mods:[lightgray]\n").append("> ").append(extraMods.toString("\n> "));
                }
                con.kick(result.toString());
            }

            if(!admins.isWhitelisted(packet.uuid, packet.usid)){
                info.adminUsid = packet.usid;
                info.lastName = packet.name;
                info.id = packet.uuid;
                admins.save();
                Call.onInfoMessage(con, "You are not whitelisted here.");
                Log.info("&lcDo &lywhitelist-add @&lc to whitelist the player &lb'@'", packet.uuid, packet.name);
                con.kick(KickReason.whitelist);
                return;
            }

            if(packet.versionType == null || ((packet.version == -1 || !packet.versionType.equals(Version.type)) && Version.build != -1 && !admins.allowsCustomClients())){
                con.kick(!Version.type.equals(packet.versionType) ? KickReason.typeMismatch : KickReason.customClient);
                return;
            }

            boolean preventDuplicates = headless && netServer.admins.getStrict();

            if(preventDuplicates){
                if(Groups.player.contains(p -> p.name().trim().equalsIgnoreCase(packet.name.trim()))){
                    con.kick(KickReason.nameInUse);
                    return;
                }

                if(Groups.player.contains(player -> player.uuid().equals(packet.uuid) || player.usid().equals(packet.usid))){
                    con.kick(KickReason.idInUse);
                    return;
                }
            }

            packet.name = fixName(packet.name);

            if(packet.name.trim().length() <= 0){
                con.kick(KickReason.nameEmpty);
                return;
            }

            String ip = con.address;

            admins.updatePlayerJoined(uuid, ip, packet.name);

            if(packet.version != Version.build && Version.build != -1 && packet.version != -1){
                con.kick(packet.version > Version.build ? KickReason.serverOutdated : KickReason.clientOutdated);
                return;
            }

            if(packet.version == -1){
                con.modclient = true;
            }

            Playerc player = PlayerEntity.create();
            player.admin(admins.isAdmin(uuid, packet.usid));
            player.con(con);
            player.con().usid = packet.usid;
            player.con().uuid = uuid;
            player.con().mobile = packet.mobile;
            player.name(packet.name);
            player.color().set(packet.color).a(1f);

            //save admin ID but don't overwrite it
            if(!player.admin() && !info.admin){
                info.adminUsid = packet.usid;
            }

            try{
                writeBuffer.reset();
                player.write(outputBuffer);
            }catch(Throwable t){
                t.printStackTrace();
                con.kick(KickReason.nameEmpty);
                return;
            }

            con.player = player;

            //playing in pvp mode automatically assigns players to teams
            player.team(assignTeam(player));

            sendWorldData(player);

            platform.updateRPC();

            Events.fire(new PlayerConnect(player));
        });

        net.handleServer(InvokePacket.class, (con, packet) -> {
            if(con.player == null) return;
            try{
                RemoteReadServer.readPacket(packet.reader(), packet.type, con.player);
            }catch(ValidateException e){
                Log.debug("Validation failed for '@': @", e.player, e.getMessage());
            }catch(RuntimeException e){
                if(e.getCause() instanceof ValidateException){
                    ValidateException v = (ValidateException)e.getCause();
                    Log.debug("Validation failed for '@': @", v.player, v.getMessage());
                }else{
                    throw e;
                }
            }
        });

        registerCommands();
    }

    @Override
    public void init(){
        mods.eachClass(mod -> mod.registerClientCommands(clientCommands));
    }

    private void registerCommands(){
        clientCommands.<Playerc>register("help", "[page]", "Lists all commands.", (args, player) -> {
            if(args.length > 0 && !Strings.canParseInt(args[0])){
                player.sendMessage("[scarlet]'page' must be a number.");
                return;
            }
            int commandsPerPage = 6;
            int page = args.length > 0 ? Strings.parseInt(args[0]) : 1;
            int pages = Mathf.ceil((float)clientCommands.getCommandList().size / commandsPerPage);

            page --;

            if(page >= pages || page < 0){
                player.sendMessage("[scarlet]'page' must be a number between[orange] 1[] and[orange] " + pages + "[scarlet].");
                return;
            }

            StringBuilder result = new StringBuilder();
            result.append(Strings.format("[orange]-- Commands Page[lightgray] @[gray]/[lightgray]@[orange] --\n\n", (page+1), pages));

            for(int i = commandsPerPage * page; i < Math.min(commandsPerPage * (page + 1), clientCommands.getCommandList().size); i++){
                Command command = clientCommands.getCommandList().get(i);
                result.append("[orange] /").append(command.text).append("[white] ").append(command.paramText).append("[lightgray] - ").append(command.description).append("\n");
            }
            player.sendMessage(result.toString());
        });

        clientCommands.<Playerc>register("t", "<message...>", "Send a message only to your teammates.", (args, player) -> {
            String message = admins.filterMessage(player, args[0]);
            if(message != null){
                Groups.player.each(p -> p.team() == player.team(), o -> o.sendMessage(message, player, "[#" + player.team().color.toString() + "]<T>" + NetClient.colorizeName(player.id(), player.name())));
            }
        });

        //duration of a a kick in seconds
        int kickDuration = 60 * 60;
        //voting round duration in seconds
        float voteDuration = 0.5f * 60;
        //cooldown between votes in seconds
        int voteCooldown = 60 * 5;

        class VoteSession{
            Playerc target;
            ObjectSet<String> voted = new ObjectSet<>();
            VoteSession[] map;
            Timer.Task task;
            int votes;

            public VoteSession(VoteSession[] map, Playerc target){
                this.target = target;
                this.map = map;
                this.task = Timer.schedule(() -> {
                    if(!checkPass()){
                        Call.sendMessage(Strings.format("[lightgray]Vote failed. Not enough votes to kick[orange] @[lightgray].", target.name()));
                        map[0] = null;
                        task.cancel();
                    }
                }, voteDuration);
            }

            void vote(Playerc player, int d){
                votes += d;
                voted.addAll(player.uuid(), admins.getInfo(player.uuid()).lastIP);
                        
                Call.sendMessage(Strings.format("[orange]@[lightgray] has voted on kicking[orange] @[].[accent] (@/@)\n[lightgray]Type[orange] /vote <y/n>[] to agree.",
                            player.name(), target.name(), votes, votesRequired()));
            }

            boolean checkPass(){
                if(votes >= votesRequired()){
                    Call.sendMessage(Strings.format("[orange]Vote passed.[scarlet] @[orange] will be banned from the server for @ minutes.", target.name(), (kickDuration/60)));
                    target.getInfo().lastKicked = Time.millis() + kickDuration*1000;
                    Groups.player.each(p -> p.uuid().equals(target.uuid()), p -> p.kick(KickReason.vote));
                    map[0] = null;
                    task.cancel();
                    return true;
                }
                return false;
            }
        }

        //cooldowns per player
        ObjectMap<String, Timekeeper> cooldowns = new ObjectMap<>();
        //current kick sessions
        VoteSession[] currentlyKicking = {null};

        clientCommands.<Playerc>register("votekick", "[player...]", "Vote to kick a player.", (args, player) -> {
            if(!Config.enableVotekick.bool()){
                player.sendMessage("[scarlet]Vote-kick is disabled on this server.");
                return;
            }

            if(Groups.player.size() < 3){
                player.sendMessage("[scarlet]At least 3 players are needed to start a votekick.");
                return;
            }

            if(player.isLocal()){
                player.sendMessage("[scarlet]Just kick them yourself if you're the host.");
                return;
            }

            if(args.length == 0){
                StringBuilder builder = new StringBuilder();
                builder.append("[orange]Players to kick: \n");

                Groups.player.each(p -> !p.admin() && p.con() != null && p != player, p -> {
                    builder.append("[lightgray] ").append(p.name()).append("[accent] (#").append(p.id()).append(")\n");
                });
                player.sendMessage(builder.toString());
            }else{
                Playerc found;
                if(args[0].length() > 1 && args[0].startsWith("#") && Strings.canParseInt(args[0].substring(1))){
                    int id = Strings.parseInt(args[0].substring(1));
                    found = Groups.player.find(p -> p.id() == id);
                }else{
                    found = Groups.player.find(p -> p.name().equalsIgnoreCase(args[0]));
                }

                if(found != null){
                    if(found.admin()){
                        player.sendMessage("[scarlet]Did you really expect to be able to kick an admin?");
                    }else if(found.isLocal()){
                        player.sendMessage("[scarlet]Local players cannot be kicked.");
                    }else if(found.team() != player.team()){
                        player.sendMessage("[scarlet]Only players on your team can be kicked.");
                    }else{
                        Timekeeper vtime = cooldowns.get(player.uuid(), () -> new Timekeeper(voteCooldown));

                        if(!vtime.get()){
                            player.sendMessage("[scarlet]You must wait " + voteCooldown/60 + " minutes between votekicks.");
                            return;
                        }

                        VoteSession session = new VoteSession(currentlyKicking, found);
                        session.vote(player, 1);
                        vtime.reset();                  
                        currentlyKicking[0] = session;
                    }
                }else{
                    player.sendMessage("[scarlet]No player[orange]'" + args[0] + "'[scarlet] found.");
                }
            }
        });

        clientCommands.<Playerc>register("vote", "<y/n>", "Vote to kick the current player.", (arg, player) -> {
            if(currentlyKicking[0] == null){
                player.sendMessage("[scarlet]Nobody is being voted on.");
            }else{
                if(player.isLocal()){
                    player.sendMessage("Local players can't vote. Kick the player yourself instead.");
                    return;
                }

                //hosts can vote all they want
                if((currentlyKicking[0].voted.contains(player.uuid()) || currentlyKicking[0].voted.contains(admins.getInfo(player.uuid()).lastIP))){
                    player.sendMessage("[scarlet]You've already voted. Sit down.");
                    return;
                }

                if(currentlyKicking[0].target == player){
                    player.sendMessage("[scarlet]You can't vote on your own trial.");
                    return;
                }

                if(!arg[0].toLowerCase().equals("y") && !arg[0].toLowerCase().equals("n")){
                    player.sendMessage("[scarlet]Vote either 'y' (yes) or 'n' (no).");
                    return;
                }

                int sign = arg[0].toLowerCase().equals("y") ? 1 : -1;
                currentlyKicking[0].vote(player, sign);
            }
        });

        clientCommands.<Playerc>register("sync", "Re-synchronize world state.", (args, player) -> {
            if(player.isLocal()){
                player.sendMessage("[scarlet]Re-synchronizing as the host is pointless.");
            }else{
                if(Time.timeSinceMillis(player.getInfo().lastSyncTime) < 1000 * 5){
                    player.sendMessage("[scarlet]You may only /sync every 5 seconds.");
                    return;
                }

                player.getInfo().lastSyncTime = Time.millis();
                Call.onWorldDataBegin(player.con());
                netServer.sendWorldData(player);
            }
        });
    }

    public int votesRequired(){
        return 2 + (Groups.player.size() > 4 ? 1 : 0);
    }

    public Team assignTeam(Playerc current){
        return assigner.assign(current, Groups.player);
    }

    public Team assignTeam(Playerc current, Iterable<Playerc> players){
        return assigner.assign(current, players);
    }

    public void sendWorldData(Playerc player){
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        DeflaterOutputStream def = new FastDeflaterOutputStream(stream);
        NetworkIO.writeWorld(player, def);
        WorldStream data = new WorldStream();
        data.stream = new ByteArrayInputStream(stream.toByteArray());
        player.con().sendStream(data);

        Log.debug("Packed @ compressed bytes of world data.", stream.size());
    }

    public void addPacketHandler(String type, Cons2<Playerc, String> handler){
        customPacketHandlers.get(type, Array::new).add(handler);
    }

    public Array<Cons2<Playerc, String>> getPacketHandlers(String type){
        return customPacketHandlers.get(type, Array::new);
    }

    public static void onDisconnect(Playerc player, String reason){
        //singleplayer multiplayer wierdness
        if(player.con() == null){
            player.remove();
            return;
        }

        if(!player.con().hasDisconnected){
            if(player.con().hasConnected){
                Events.fire(new PlayerLeave(player));
                if(Config.showConnectMessages.bool()) Call.sendMessage("[accent]" + player.name() + "[accent] has disconnected.");
                Call.onPlayerDisconnect(player.id());
            }

            if(Config.showConnectMessages.bool()) Log.info("&lm[@] &lc@ has disconnected. &lg&fi(@)", player.uuid(), player.name(), reason);
        }

        player.remove();
        player.con().hasDisconnected = true;
    }

    @Remote(targets = Loc.client)
    public static void serverPacketReliable(Playerc player, String type, String contents){
        if(netServer.customPacketHandlers.containsKey(type)){
            for(Cons2<Playerc, String> c : netServer.customPacketHandlers.get(type)){
                c.get(player, contents);
            }
        }
    }

    @Remote(targets = Loc.client, unreliable = true)
    public static void serverPacketUnreliable(Playerc player, String type, String contents){
        serverPacketReliable(player, type, contents);
    }

    @Remote(targets = Loc.client, unreliable = true)
    public static void onClientShapshot(
        Playerc player,
        int snapshotID,
        float x, float y,
        float pointerX, float pointerY,
        float rotation, float baseRotation,
        float xVelocity, float yVelocity,
        Tile mining,
        boolean boosting, boolean shooting, boolean chatting,
        @Nullable BuildRequest[] requests,
        float viewX, float viewY, float viewWidth, float viewHeight
    ){
        NetConnection connection = player.con();
        if(connection == null || snapshotID < connection.lastRecievedClientSnapshot) return;

        boolean verifyPosition = !player.dead() && netServer.admins.getStrict() && headless;

        if(connection.lastRecievedClientTime == 0) connection.lastRecievedClientTime = Time.millis() - 16;

        connection.viewX = viewX;
        connection.viewY = viewY;
        connection.viewWidth = viewWidth;
        connection.viewHeight = viewHeight;

        //disable shooting when a mech flies
        if(!player.dead() && player.unit().isFlying() && player.unit() instanceof Mechc){
            shooting = false;
        }

        player.mouseX(pointerX);
        player.mouseY(pointerY);
        player.typing(chatting);
        player.shooting(shooting);
        player.boosting(boosting);

        player.unit().controlWeapons(shooting, shooting);
        player.unit().aim(pointerX, pointerY);

        if(player.isBuilder()){
            player.builder().clearBuilding();
        }

        if(player.isMiner()){
            player.miner().mineTile(mining);
        }

        if(requests != null){
            for(BuildRequest req : requests){
                if(req == null) continue;
                Tile tile = world.tile(req.x, req.y);
                if(tile == null || (!req.breaking && req.block == null)) continue;
                //auto-skip done requests
                if(req.breaking && tile.block() == Blocks.air){
                    continue;
                }else if(!req.breaking && tile.block() == req.block && (!req.block.rotate || tile.rotation() == req.rotation)){
                    continue;
                }else if(connection.rejectedRequests.contains(r -> r.breaking == req.breaking && r.x == req.x && r.y == req.y)){ //check if request was recently rejected, and skip it if so
                    continue;
                }else if(!netServer.admins.allowAction(player, req.breaking ? ActionType.breakBlock : ActionType.placeBlock, tile, action -> { //make sure request is allowed by the server
                    action.block = req.block;
                    action.rotation = req.rotation;
                    action.config = req.config;
                })){
                    //force the player to remove this request if that's not the case
                    Call.removeQueueBlock(player.con(), req.x, req.y, req.breaking);
                    connection.rejectedRequests.add(req);
                    continue;
                }
                player.builder().requests().addLast(req);
            }
        }

        connection.rejectedRequests.clear();

        if(!player.dead()){
            Unitc unit = player.unit();
            long elapsed = Time.timeSinceMillis(connection.lastRecievedClientTime);
            float maxSpeed = player.dead() ? Float.MAX_VALUE : player.unit().type().speed;
            float maxMove = elapsed / 1000f * 60f * maxSpeed * 1.1f;

            if(connection.lastUnit != unit && !player.dead()){
                connection.lastUnit = unit;
                connection.lastPosition.set(unit);
            }

            vector.set(x, y).sub(connection.lastPosition);
            vector.limit(maxMove);

            float prevx = unit.x(), prevy = unit.y();
            unit.set(connection.lastPosition);
            if(!unit.isFlying()){
                unit.move(vector.x, vector.y);
            }else{
                unit.trns(vector.x, vector.y);
            }

            //set last position after movement
            connection.lastPosition.set(unit);
            float newx = unit.x(), newy = unit.y();

            if(!verifyPosition){
                unit.set(prevx, prevy);
                newx = x;
                newy = y;
            }else if(!Mathf.within(x, y, newx, newy, correctDist)){
                Call.onPositionSet(player.con(), newx, newy); //teleport and correct position when necessary
            }

            //reset player to previous synced position so it gets interpolated
            unit.set(prevx, prevy);

            //write sync data to the buffer
            fbuffer.limit(20);
            fbuffer.position(0);

            //now, put the new position, rotation and baserotation into the buffer so it can be read
            if(unit instanceof Mechc) fbuffer.put(baseRotation); //base rotation is optional
            fbuffer.put(unit.elevation());
            fbuffer.put(rotation); //rotation is always there
            fbuffer.put(newx);
            fbuffer.put(newy);
            fbuffer.flip();

            //read sync data so it can be used for interpolation for the server
            unit.readSyncManual(fbuffer);

            //TODO clients shouldn't care about velocities, so should it always just get set to 0? why even save it?
            //[[ignore sent velocity values, set it to the delta movement vector instead]]
            //unit.vel().set(vector);
        }else{
            player.x(x);
            player.y(y);
        }

        connection.lastRecievedClientSnapshot = snapshotID;
        connection.lastRecievedClientTime = Time.millis();
    }

    @Remote(targets = Loc.client, called = Loc.server)
    public static void onAdminRequest(Playerc player, Playerc other, AdminAction action){

        if(!player.admin()){
            Log.warn("ACCESS DENIED: Player @ / @ attempted to perform admin action without proper security access.",
            player.name(), player.con().address);
            return;
        }

        if(other == null || ((other.admin() && !player.isLocal()) && other != player)){
            Log.warn("@ attempted to perform admin action on nonexistant or admin player.", player.name());
            return;
        }

        if(action == AdminAction.wave){
            //no verification is done, so admins can hypothetically spam waves
            //not a real issue, because server owners may want to do just that
            state.wavetime = 0f;
        }else if(action == AdminAction.ban){
            netServer.admins.banPlayerIP(other.con().address);
            other.kick(KickReason.banned);
            Log.info("&lc@ has banned @.", player.name(), other.name());
        }else if(action == AdminAction.kick){
            other.kick(KickReason.kick);
            Log.info("&lc@ has kicked @.", player.name(), other.name());
        }else if(action == AdminAction.trace){
            TraceInfo info = new TraceInfo(other.con().address, other.uuid(), other.con().modclient, other.con().mobile);
            if(player.con() != null){
                Call.onTraceInfo(player.con(), other, info);
            }else{
                NetClient.onTraceInfo(other, info);
            }
            Log.info("&lc@ has requested trace info of @.", player.name(), other.name());
        }
    }

    @Remote(targets = Loc.client)
    public static void connectConfirm(Playerc player){
        if(player.con() == null || player.con().hasConnected) return;

        player.add();
        player.con().hasConnected = true;
        if(Config.showConnectMessages.bool()){
            Call.sendMessage("[accent]" + player.name() + "[accent] has connected.");
            Log.info("&lm[@] &y@ has connected. ", player.uuid(), player.name());
        }

        if(!Config.motd.string().equalsIgnoreCase("off")){
            player.sendMessage(Config.motd.string());
        }

        Events.fire(new PlayerJoin(player));
    }

    public boolean isWaitingForPlayers(){
        if(state.rules.pvp){
            int used = 0;
            for(TeamData t : state.teams.getActive()){
                if(Groups.player.count(p -> p.team() == t.team) > 0){
                    used++;
                }
            }
            return used < 2;
        }
        return false;
    }

    @Override
    public void update(){

        if(!headless && !closing && net.server() && state.isMenu()){
            closing = true;
            ui.loadfrag.show("$server.closing");
            Time.runTask(5f, () -> {
                net.closeServer();
                ui.loadfrag.hide();
                closing = false;
            });
        }

        if(state.isGame() && net.server()){
            sync();
        }
    }

    /** Should only be used on the headless backend. */
    public void openServer(){
        try{
            net.host(Config.port.num());
            info("&lcOpened a server on port @.", Config.port.num());
        }catch(BindException e){
            Log.err("Unable to host: Port already in use! Make sure no other servers are running on the same port in your network.");
            state.set(State.menu);
        }catch(IOException e){
            err(e);
            state.set(State.menu);
        }
    }

    public void kickAll(KickReason reason){
        for(NetConnection con : net.getConnections()){
            con.kick(reason);
        }
    }

    /** Sends a block snapshot to all players. */
    public void writeBlockSnapshots() throws IOException{
        syncStream.reset();

        short sent = 0;
        for(Tilec entity : Groups.tile){
            if(!entity.block().sync) continue;
            sent ++;

            dataStream.writeInt(entity.tile().pos());
            entity.writeAll(Writes.get(dataStream));

            if(syncStream.size() > maxSnapshotSize){
                dataStream.close();
                byte[] stateBytes = syncStream.toByteArray();
                Call.onBlockSnapshot(sent, (short)stateBytes.length, net.compressSnapshot(stateBytes));
                sent = 0;
                syncStream.reset();
            }
        }

        if(sent > 0){
            dataStream.close();
            byte[] stateBytes = syncStream.toByteArray();
            Call.onBlockSnapshot(sent, (short)stateBytes.length, net.compressSnapshot(stateBytes));
        }
    }

    public void writeEntitySnapshot(Playerc player) throws IOException{
        syncStream.reset();
        Array<CoreEntity> cores = state.teams.cores(player.team());

        dataStream.writeByte(cores.size);

        for(CoreEntity entity : cores){
            dataStream.writeInt(entity.tile().pos());
            entity.items().write(Writes.get(dataStream));
        }

        dataStream.close();
        byte[] stateBytes = syncStream.toByteArray();

        //write basic state data.
        Call.onStateSnapshot(player.con(), state.wavetime, state.wave, state.enemies, state.serverPaused, (short)stateBytes.length, net.compressSnapshot(stateBytes));

        viewport.setSize(player.con().viewWidth, player.con().viewHeight).setCenter(player.con().viewX, player.con().viewY);

        syncStream.reset();

        int sent = 0;

        for(Syncc entity : Groups.sync){
            //write all entities now
            dataStream.writeInt(entity.id()); //write id
            dataStream.writeByte(entity.classId()); //write type ID
            entity.writeSync(Writes.get(dataStream)); //write entity

            sent++;

            if(syncStream.size() > maxSnapshotSize){
                dataStream.close();
                byte[] syncBytes = syncStream.toByteArray();
                Call.onEntitySnapshot(player.con(), (short)sent, (short)syncBytes.length, net.compressSnapshot(syncBytes));
                sent = 0;
                syncStream.reset();
            }
        }

        if(sent > 0){
            dataStream.close();

            byte[] syncBytes = syncStream.toByteArray();
            Call.onEntitySnapshot(player.con(), (short)sent, (short)syncBytes.length, net.compressSnapshot(syncBytes));
        }

    }

    String fixName(String name){
        name = name.trim();
        if(name.equals("[") || name.equals("]")){
            return "";
        }

        for(int i = 0; i < name.length(); i++){
            if(name.charAt(i) == '[' && i != name.length() - 1 && name.charAt(i + 1) != '[' && (i == 0 || name.charAt(i - 1) != '[')){
                String prev = name.substring(0, i);
                String next = name.substring(i);
                String result = checkColor(next);

                name = prev + result;
            }
        }

        StringBuilder result = new StringBuilder();
        int curChar = 0;
        while(curChar < name.length() && result.toString().getBytes().length < maxNameLength){
            result.append(name.charAt(curChar++));
        }
        return result.toString();
    }

    String checkColor(String str){

        for(int i = 1; i < str.length(); i++){
            if(str.charAt(i) == ']'){
                String color = str.substring(1, i);

                if(Colors.get(color.toUpperCase()) != null || Colors.get(color.toLowerCase()) != null){
                    Color result = (Colors.get(color.toLowerCase()) == null ? Colors.get(color.toUpperCase()) : Colors.get(color.toLowerCase()));
                    if(result.a <= 0.8f){
                        return str.substring(i + 1);
                    }
                }else{
                    try{
                        Color result = Color.valueOf(color);
                        if(result.a <= 0.8f){
                            return str.substring(i + 1);
                        }
                    }catch(Exception e){
                        return str;
                    }
                }
            }
        }
        return str;
    }

    void sync(){

        try{
            Groups.player.each(p -> !p.isLocal(), player -> {
                if(player.con() == null || !player.con().isConnected()){
                    onDisconnect(player, "disappeared");
                    return;
                }

                NetConnection connection = player.con();

                if(!player.timer(0, serverSyncTime) || !connection.hasConnected) return;

                try{
                    writeEntitySnapshot(player);
                }catch(IOException e){
                    e.printStackTrace();
                }
            });

            if(Groups.player.size() > 0 && Core.settings.getBool("blocksync") && timer.get(timerBlockSync, blockSyncTime)){
                writeBlockSnapshots();
            }

        }catch(IOException e){
            e.printStackTrace();
        }
    }

    public interface TeamAssigner{
        Team assign(Playerc player, Iterable<Playerc> players);
    }
}
