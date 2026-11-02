package com.animedungeons.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.mob.StrayEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** Initial playable foundation. See README for explicit implementation limits. */
public class AnimeDungeonsMod implements ModInitializer {
    public static final String MOD_ID = "animedungeons";
    private final Map<UUID, Integer> xp = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> partyOf = new ConcurrentHashMap<>();
    private final Map<UUID, Party> parties = new ConcurrentHashMap<>();
    private final Map<UUID, DungeonInstance> instances = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerInstance = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> invites = new ConcurrentHashMap<>();
    private final Properties config = new Properties();
    private MinecraftServer server;
    private Path dataFile;
    private int nextInstance = 1;
    private long tickCounter = 0;

    @Override public void onInitialize() {
        loadConfigDefaults();
        ServerLifecycleEvents.SERVER_STARTED.register(s -> { server = s; loadData(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> saveData());
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            tickCounter++;
            if (tickCounter % 1200 == 0) saveData();
            tickInstances(s);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> onDisconnect(handler.getPlayer()));
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> registerCommands(dispatcher));
        UseItemCallback.EVENT.register((player, world, hand) -> useAbility(player, world, hand));
    }

    private void loadConfigDefaults() {
        config.setProperty("entrance_x", "0"); config.setProperty("entrance_y", "80"); config.setProperty("entrance_z", "0");
        String[] ranks = {"F","E","D","C","B","A","S"};
        int[] levels = {1,5,10,20,35,50,70};
        for (int i=0;i<ranks.length;i++) config.setProperty("minimum_level_"+ranks[i], String.valueOf(levels[i]));
        config.setProperty("base_rooms", "10"); config.setProperty("rooms_per_rank", "4"); config.setProperty("room_spacing", "13");
        config.setProperty("cooldown_minutes", "15"); config.setProperty("xp_per_kill", "8"); config.setProperty("xp_per_boss", "100");
        config.setProperty("public_boss_enabled", "true");
    }

    private int cfg(String key, int fallback) { try { return Integer.parseInt(config.getProperty(key, String.valueOf(fallback))); } catch (Exception e) { return fallback; } }
    private boolean cfgBool(String key, boolean fallback) { return Boolean.parseBoolean(config.getProperty(key, String.valueOf(fallback))); }
    private int level(UUID id) { return 1 + (int)Math.floor(Math.sqrt(Math.max(0, xp.getOrDefault(id, 0)) / 100.0)); }
    private String rankName(int n) { return new String[]{"F","E","D","C","B","A","S"}[MathHelper.clamp(n,0,6)]; }
    private int rankIndex(String r) { String[] rs={"F","E","D","C","B","A","S"}; for(int i=0;i<rs.length;i++) if(rs[i].equalsIgnoreCase(r)) return i; return -1; }

    private void registerCommands(com.mojang.brigadier.CommandDispatcher<net.minecraft.server.command.ServerCommandSource> d) {
        d.register(literal("adungeon")
            .executes(c -> { c.getSource().sendFeedback(new LiteralText("§bAnimeDungeons §7| /adungeon locate, enter <F-S>, exit, level, party ..."), false); return 1; })
            .then(literal("locate").executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayer(); if (p == null) return 0;
                int x=cfg("entrance_x",0), y=cfg("entrance_y",80), z=cfg("entrance_z",0);
                c.getSource().sendFeedback(new LiteralText("§bورودی دانجن: §f"+x+", "+y+", "+z+" §7(قابل تنظیم در config)"), false); return 1;
            }))
            .then(literal("level").executes(c -> { ServerPlayerEntity p=c.getSource().getPlayer(); if(p==null)return 0; c.getSource().sendFeedback(new LiteralText("§bAdventure Level: §f"+level(p.getUuid())+" §7| XP: "+xp.getOrDefault(p.getUuid(),0)), false); return 1; }))
            .then(literal("enter").then(argument("rank", StringArgumentType.word()).executes(c -> enter(c.getSource().getPlayer(), StringArgumentType.getString(c,"rank")))))
            .then(literal("exit").executes(c -> exit(c.getSource().getPlayer(), false)))
            .then(literal("generate").then(argument("rank", StringArgumentType.word()).requires(src -> src.hasPermissionLevel(2)).executes(c -> enter(c.getSource().getPlayer(), StringArgumentType.getString(c,"rank")))))
            .then(literal("cancel").requires(src -> src.hasPermissionLevel(2)).executes(c -> exit(c.getSource().getPlayer(), true)))
            .then(literal("party")
                .then(literal("create").executes(c -> createParty(c.getSource().getPlayer())))
                .then(literal("invite").then(argument("player", StringArgumentType.word()).executes(c -> invite(c.getSource().getPlayer(), StringArgumentType.getString(c,"player")))))
                .then(literal("join").then(argument("leader", StringArgumentType.word()).executes(c -> joinParty(c.getSource().getPlayer(), StringArgumentType.getString(c,"leader")))))
                .then(literal("leave").executes(c -> leaveParty(c.getSource().getPlayer())))
                .then(literal("info").executes(c -> partyInfo(c.getSource().getPlayer())))
            )
        );
    }

    private int enter(ServerPlayerEntity player, String rank) {
        if (player == null) return 0;
        int ri=rankIndex(rank); if(ri<0) { tell(player,"§cدرجه معتبر نیست؛ F تا S."); return 0; }
        String r=rankName(ri); int min=cfg("minimum_level_"+r,1+ri*5);
        if(level(player.getUuid())<min) { tell(player,"§cبرای دانجن "+r+" به Level "+min+" نیاز داری."); return 0; }
        if(playerInstance.containsKey(player.getUuid())) { tell(player,"§cتو همین حالا داخل یک دانجن هستی."); return 0; }
        long until=cooldownUntil.getOrDefault(player.getUuid(),0L);
        if(System.currentTimeMillis()<until) { tell(player,"§cهنوز cooldown داری."); return 0; }
        List<ServerPlayerEntity> members = partyMembers(player);
        for(ServerPlayerEntity m:members) if(playerInstance.containsKey(m.getUuid())) { tell(player,"§cیکی از اعضای پارتی داخل دانجن دیگری است."); return 0; }
        ServerWorld world=player.getServerWorld();
        int id=nextInstance++; int baseX=cfg("entrance_x",0)+(id*320); int baseZ=cfg("entrance_z",0);
        DungeonInstance inst=new DungeonInstance(UUID.randomUUID(), id, r, world, baseX, Math.max(20,cfg("entrance_y",80)), baseZ, members);
        instances.put(inst.id,inst);
        buildDungeon(inst,ri);
        for(ServerPlayerEntity m:members) {
            playerInstance.put(m.getUuid(),inst.id);
            m.teleport(world,baseX+2,inst.y+2,baseZ+2,m.getYaw(),m.getPitch());
            tell(m,"§bAnimeDungeons §7| ورود به دانجن رتبه §e"+r+"§7. مراقب باش!");
        }
        spawnBoss(inst,ri,true);
        return 1;
    }

    private void buildDungeon(DungeonInstance in, int ri) {
        int rooms=Math.max(5,cfg("base_rooms",10)+ri*cfg("rooms_per_rank",4));
        int spacing=Math.max(11,cfg("room_spacing",13));
        Random random=new Random(in.id.getMostSignificantBits() ^ in.numericId*7919L ^ ri*104729L);
        BlockState floor=ri<2?Blocks.PACKED_ICE.getDefaultState():Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();
        BlockState wall=ri<2?Blocks.BLUE_ICE.getDefaultState():Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();
        BlockState accent=ri<2?Blocks.CRYING_OBSIDIAN.getDefaultState():Blocks.CRYING_OBSIDIAN.getDefaultState();
        // A winding chain of large rooms over three vertical layers; each seed changes direction and room dimensions.
        int x=in.x, z=in.z, y=in.y;
        for(int i=0;i<rooms;i++) {
            int layer=i%3; int w=11+random.nextInt(9)+ri*2; int d=11+random.nextInt(9)+ri*2;
            int cy=y-layer*9; int cx=x+(i*spacing)+(random.nextInt(7)-3); int cz=z+(random.nextInt(19)-9);
            fill(in.world,new BlockPos(cx-w/2,cy,cz-d/2),new BlockPos(cx+w/2,cy,cz+d/2),floor);
            // room shell, pillars, broken/ruined-looking corners, crystals and low ceiling
            for(int xx=cx-w/2;xx<=cx+w/2;xx++) for(int zz=cz-d/2;zz<=cz+d/2;zz++) {
                if(Math.abs(xx-cx)==w/2 || Math.abs(zz-cz)==d/2) {
                    for(int yy=1;yy<=5;yy++) if(random.nextDouble()>.07) in.world.setBlockState(new BlockPos(xx,cy+yy,zz),wall,3);
                }
                if(random.nextDouble()<0.035) in.world.setBlockState(new BlockPos(xx,cy+1,zz),accent,3);
            }
            for(int px:new int[]{cx-w/2+2,cx+w/2-2}) for(int pz:new int[]{cz-d/2+2,cz+d/2-2})
                for(int yy=1;yy<=4;yy++) in.world.setBlockState(new BlockPos(px,cy+yy,pz),wall,3);
            // corridors to next room
            for(int step=0;step<spacing;step++) for(int side=-1;side<=1;side++) {
                int bx=x+i*spacing+step, bz=cz+side;
                in.world.setBlockState(new BlockPos(bx,cy,bz),floor,3);
                in.world.setBlockState(new BlockPos(bx,cy+1,bz),Blocks.AIR.getDefaultState(),3);
                in.world.setBlockState(new BlockPos(bx,cy+2,bz),Blocks.AIR.getDefaultState(),3);
            }
            if(i>0 && i%4==0) spawnElite(in,ri,cx,cy+1,cz);
            if(random.nextDouble()<0.45) {
                BlockPos crystal=new BlockPos(cx+random.nextInt(Math.max(1,w/2))-w/4,cy+1,cz+random.nextInt(Math.max(1,d/2))-d/4);
                in.world.setBlockState(crystal,ri<3?Blocks.SEA_LANTERN.getDefaultState():Blocks.SOUL_LANTERN.getDefaultState(),3);
            }
        }
        // entry platform and return anchor
        fill(in.world,new BlockPos(x-3,y-1,z-3),new BlockPos(x+5,y-1,z+5),Blocks.POLISHED_BLACKSTONE.getDefaultState());
        in.world.setBlockState(new BlockPos(x,y,z),Blocks.LODESTONE.getDefaultState(),3);
        in.spawn=new BlockPos(x+2,y+1,z+2);
    }
    
    private void fill(ServerWorld w, BlockPos a, BlockPos b, BlockState state) {
        for(int x=a.getX();x<=b.getX();x++) for(int y=a.getY();y<=b.getY();y++) for(int z=a.getZ();z<=b.getZ();z++) w.setBlockState(new BlockPos(x,y,z),state,3);
    }

    private void spawnElite(DungeonInstance in,int ri,int x,int y,int z) {
        MobEntity mob = ri<2 ? new StrayEntity(EntityType.STRAY,in.world) : new ZombieEntity(EntityType.ZOMBIE,in.world);
        mob.refreshPositionAndAngles(x,y,z,0,0); mob.setCustomName(new LiteralText("§5"+(ri<2?"Frostbound Warden":"Cursed Elite"))); mob.setCustomNameVisible(true);
        mob.setPersistent(); mob.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, Integer.MAX_VALUE, Math.min(ri,4)));
        mob.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, Integer.MAX_VALUE, Math.min(ri/2,2)));
        in.world.spawnEntity(mob); in.elites.add(mob.getUuid());
    }

    private void spawnBoss(DungeonInstance in,int ri,boolean privateBoss) {
        int bx=in.x+Math.max(5,cfg("base_rooms",10)+ri*cfg("rooms_per_rank",4))*Math.max(11,cfg("room_spacing",13));
        int by=in.y-((Math.max(5,cfg("base_rooms",10)+ri*cfg("rooms_per_rank",4))-1)%3)*9+1; int bz=in.z;
        ZombieEntity boss=new ZombieEntity(EntityType.ZOMBIE,in.world);
        boss.refreshPositionAndAngles(bx,by,bz,0,0); boss.setCustomName(new LiteralText("§c§l"+rankName(ri)+" Rank — "+(ri<2?"Glacial Revenant":"Abyssal Monarch"))); boss.setCustomNameVisible(true); boss.setPersistent();
        boss.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, Integer.MAX_VALUE, Math.min(ri+1,6)));
        boss.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, Integer.MAX_VALUE, Math.min(ri,4)));
        boss.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, Integer.MAX_VALUE, Math.min(ri/2,2)));
        in.world.spawnEntity(boss); in.boss=boss.getUuid(); in.bossBar=new ServerBossBar(boss.getDisplayName(), BossBar.Color.PURPLE, BossBar.Style.PROGRESS);
        in.bossBar.setVisible(true); for(ServerPlayerEntity p:in.members) in.bossBar.addPlayer(p);
    }

    private int exit(ServerPlayerEntity p, boolean admin) {
        if(p==null)return 0; UUID id=playerInstance.remove(p.getUuid()); if(id==null){tell(p,"§7تو داخل دانجن نیستی.");return 0;}
        DungeonInstance in=instances.get(id); if(in!=null){in.members.removeIf(m->m.getUuid().equals(p.getUuid())); if(in.bossBar!=null)in.bossBar.removePlayer(p);}
        p.teleport(p.getServer().getOverworld(), cfg("entrance_x",0),cfg("entrance_y",80),cfg("entrance_z",0),p.getYaw(),p.getPitch()); tell(p,"§7از دانجن خارج شدی.");
        if(in!=null && in.members.isEmpty()) finishInstance(in,false);
        return 1;
    }

    private void finishInstance(DungeonInstance in, boolean won) {
        if(in.finished)return; in.finished=true;
        if(in.bossBar!=null)in.bossBar.setVisible(false);
        for(ServerPlayerEntity p:new ArrayList<>(in.members)) {
            playerInstance.remove(p.getUuid());
            if(won) { xp.merge(p.getUuid(),cfg("xp_per_boss",100),Integer::sum); giveReward(p,rankIndex(in.rank)); tell(p,"§aدانجن پاک‌سازی شد! پاداش دریافت کردی."); }
            else { cooldownUntil.put(p.getUuid(),System.currentTimeMillis()+cfg("cooldown_minutes",15)*60_000L); tell(p,"§cدانجن ناموفق بود؛ cooldown فعال شد."); }
            p.teleport(p.getServer().getOverworld(),cfg("entrance_x",0),cfg("entrance_y",80),cfg("entrance_z",0),p.getYaw(),p.getPitch());
        }
        instances.remove(in.id);
        // Blocks are not automatically erased: this is intentional to avoid destructive world edits in the prototype.
    }

    private void tickInstances(MinecraftServer s) {
        for(DungeonInstance in:new ArrayList<>(instances.values())) {
            if(in.finished)continue;
            Entity boss=in.world.getEntity(in.boss);
            if(boss==null || !boss.isAlive()) { finishInstance(in,true); continue; }
            if(in.bossBar!=null && boss instanceof LivingEntity) in.bossBar.setPercent(((LivingEntity)boss).getHealth()/((LivingEntity)boss).getMaxHealth());
            // Safety: if all members leave/disconnect, close this instance.
            in.members.removeIf(p->!p.isAlive() || p.isRemoved());
            if(in.members.isEmpty()) finishInstance(in,false);
        }
    }

    private ActionResult<ItemStack> useAbility(PlayerEntity player, World world, Hand hand) {
        ItemStack stack=player.getStackInHand(hand); if(stack.isEmpty() || !stack.hasNbt())return ActionResult.PASS;
        NbtCompound n=stack.getNbt(); if(n==null || !n.getBoolean("AnimeDungeonsAbility"))return ActionResult.PASS;
        if(world.isClient || !(player instanceof ServerPlayerEntity))return ActionResult.SUCCESS;
        ServerPlayerEntity p=(ServerPlayerEntity)player;
        if(p.getItemCooldownManager().isCoolingDown(stack.getItem())) { tell(p,"§cتوانایی هنوز آماده نیست."); return ActionResult.FAIL; }
        ServerWorld sw=p.getServerWorld();
        // A real server-side area ability: short-range arcane burst, damage and slow.
        for(Entity e:sw.getOtherEntities(p,p.getBoundingBox().expand(5.0),entity->entity instanceof LivingEntity && entity!=p)) {
            LivingEntity target=(LivingEntity)e; target.damage(net.minecraft.entity.damage.DamageSource.player(p), 8.0F);
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS,60,1));
        }
        sw.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME,p.getX(),p.getY()+1,p.getZ(),45,2.0,1.0,2.0,0.04);
        p.getItemCooldownManager().set(stack.getItem(),100); tell(p,"§dArcane Burst!"); return ActionResult.SUCCESS;
    }

    private void giveReward(ServerPlayerEntity p,int ri) {
        Random random=new Random(); int rolls=2+ri*2;
        for(int i=0;i<rolls;i++) {
            ItemStack item;
            double r=random.nextDouble();
            if(ri>=4 && r<0.08+ri*0.015) item=new ItemStack(Items.NETHERITE_INGOT,1+ri/3);
            else if(ri>=2 && r<0.24) item=new ItemStack(Items.DIAMOND,1+random.nextInt(2+ri));
            else if(r<0.55) item=new ItemStack(ri<2?Items.IRON_INGOT:Items.GOLD_INGOT,2+ri+random.nextInt(3));
            else item=new ItemStack(ri<2?Items.IRON_SWORD:Items.DIAMOND_SWORD);
            if(item.getItem()==Items.DIAMOND_SWORD || item.getItem()==Items.IRON_SWORD) {
                item.setCustomName(new LiteralText(ri>=5?"§d§lMythic Riftblade":"§bDungeon Hunter"));
                NbtCompound tag=item.getOrCreateNbt(); tag.putBoolean("AnimeDungeonsAbility",true); tag.putInt("AnimeDungeonsRank",ri); item.setNbt(tag);
                item.addEnchantment(net.minecraft.enchantment.Enchantments.SHARPNESS,Math.min(ri+1,5));
                item.addEnchantment(net.minecraft.enchantment.Enchantments.UNBREAKING,Math.min(ri+1,3));
            }
            if(!p.inventory.insertStack(item)) p.dropItem(item,false);
        }
        xp.merge(p.getUuid(),Math.max(10,(ri+1)*20),Integer::sum);
    }

    private int createParty(ServerPlayerEntity p) {
        if(p==null)return 0; if(partyOf.containsKey(p.getUuid())) {tell(p,"§cاز قبل عضو پارتی هستی.");return 0;}
        Party party=new Party(p.getUuid()); parties.put(p.getUuid(),party); partyOf.put(p.getUuid(),p.getUuid()); tell(p,"§aپارتی ساخته شد؛ برای دعوت /adungeon party invite <player>"); return 1;
    }
    private int invite(ServerPlayerEntity p,String name) {
        if(p==null)return 0; UUID leader=partyOf.get(p.getUuid()); if(leader==null || !leader.equals(p.getUuid())) {tell(p,"§cفقط رهبر پارتی می‌تواند دعوت کند.");return 0;}
        ServerPlayerEntity target=server.getPlayerManager().getPlayer(name); if(target==null){tell(p,"§cبازیکن باید آنلاین باشد.");return 0;}
        invites.put(target.getUuid(), leader);
        tell(target,"§eبرای پیوستن از دستور /adungeon party join "+p.getEntityName()+" استفاده کن.");
        tell(p,"§aدعوت برای "+target.getEntityName()+" ارسال شد.");
        return 1;
    }
    private int joinParty(ServerPlayerEntity p,String leaderName) {
        if(p==null)return 0; ServerPlayerEntity leaderPlayer=server.getPlayerManager().getPlayer(leaderName); if(leaderPlayer==null){tell(p,"§cرهبر آنلاین نیست.");return 0;}
        UUID leader=leaderPlayer.getUuid(); if(!leader.equals(invites.get(p.getUuid()))){tell(p,"§cدعوت معتبر پیدا نشد.");return 0;}
        if(partyOf.containsKey(p.getUuid())) leaveParty(p);
        Party party=parties.get(leader); if(party==null){tell(p,"§cپارتی وجود ندارد.");return 0;}
        party.members.add(p.getUuid()); partyOf.put(p.getUuid(),leader); invites.remove(p.getUuid()); tell(p,"§aبه پارتی پیوستی."); tell(leaderPlayer,"§a"+p.getEntityName()+" به پارتی پیوست."); return 1;
    }
    private int leaveParty(ServerPlayerEntity p) {
        if(p==null)return 0; UUID leader=partyOf.remove(p.getUuid()); if(leader==null){tell(p,"§7عضو پارتی نیستی.");return 0;}
        Party party=parties.get(leader); if(party!=null){party.members.remove(p.getUuid()); if(p.getUuid().equals(leader)){parties.remove(leader); for(UUID u:party.members)partyOf.remove(u); tell(p,"§eپارتی منحل شد.");} else if(party.members.isEmpty()){parties.remove(leader);partyOf.remove(leader);} }
        tell(p,"§7از پارتی خارج شدی."); return 1;
    }
    private int partyInfo(ServerPlayerEntity p) { if(p==null)return 0; UUID l=partyOf.get(p.getUuid()); Party party=l==null?null:parties.get(l); tell(p,party==null?"§7پارتی نداری.":"§bرهبر: "+server.getPlayerManager().getPlayer(l).getEntityName()+" §7| اعضا: "+party.members.size()); return 1; }
    private List<ServerPlayerEntity> partyMembers(ServerPlayerEntity p) {
        UUID leader=partyOf.get(p.getUuid()); Party party=leader==null?null:parties.get(leader); List<ServerPlayerEntity> out=new ArrayList<>();
        if(party==null){out.add(p);return out;} for(UUID id:party.members){ServerPlayerEntity member=server.getPlayerManager().getPlayer(id);if(member!=null)out.add(member);} if(out.stream().noneMatch(m->m.getUuid().equals(p.getUuid())))out.add(p); return out;
    }
    private void onDisconnect(ServerPlayerEntity p) { UUID instId=playerInstance.remove(p.getUuid()); if(instId!=null){DungeonInstance in=instances.get(instId); if(in!=null){in.members.removeIf(m->m.getUuid().equals(p.getUuid())); if(in.members.isEmpty())finishInstance(in,false);}} saveData(); }
    private void tell(ServerPlayerEntity p,String msg) { p.sendMessage(new LiteralText(msg),false); }

    private void loadData() {
        try { dataFile=server.getRunDirectory().toPath().resolve("config").resolve("animedungeons-playerdata.properties"); Files.createDirectories(dataFile.getParent());
            Properties data=new Properties(); if(Files.exists(dataFile))try(InputStream in=Files.newInputStream(dataFile)){data.load(in);}
            for(String key:data.stringPropertyNames()) { String[] parts=key.split("\\.",2); if(parts.length!=2)continue; UUID id=UUID.fromString(parts[1]); if(parts[0].equals("xp"))xp.put(id,Integer.parseInt(data.getProperty(key))); }
        } catch(Exception e){ System.err.println("[AnimeDungeons] Could not load player data: "+e.getMessage()); }
        try { Path cfgPath=server.getRunDirectory().toPath().resolve("config").resolve("animedungeons.properties"); if(!Files.exists(cfgPath)){Files.createDirectories(cfgPath.getParent());try(OutputStream out=Files.newOutputStream(cfgPath)){config.store(out,"AnimeDungeons configuration");}} else try(InputStream in=Files.newInputStream(cfgPath)){config.load(in);} } catch(Exception e){System.err.println("[AnimeDungeons] Could not load config: "+e.getMessage());}
    }
    private void saveData() {
        if(server==null)return; try { if(dataFile==null){dataFile=server.getRunDirectory().toPath().resolve("config").resolve("animedungeons-playerdata.properties");Files.createDirectories(dataFile.getParent());}
            Properties data=new Properties(); xp.forEach((id,value)->data.setProperty("xp."+id,value.toString())); cooldownUntil.forEach((id,value)->data.setProperty("cooldown."+id,value.toString()));
            try(OutputStream out=Files.newOutputStream(dataFile)){data.store(out,"AnimeDungeons player progression");}
        } catch(Exception e){System.err.println("[AnimeDungeons] Could not save player data: "+e.getMessage());}
    }

    private static final class Party { final UUID leader; final Set<UUID> members=new LinkedHashSet<>(); Party(UUID leader){this.leader=leader;members.add(leader);} }
    private static final class DungeonInstance {
        final UUID id; final int numericId; final String rank; final ServerWorld world; final int x,y,z; final List<ServerPlayerEntity> members=new ArrayList<>(); final Set<UUID> elites=new HashSet<>();
        BlockPos spawn; UUID boss; ServerBossBar bossBar; boolean finished=false;
        DungeonInstance(UUID id,int numericId,String rank,ServerWorld world,int x,int y,int z,List<ServerPlayerEntity> members){this.id=id;this.numericId=numericId;this.rank=rank;this.world=world;this.x=x;this.y=y;this.z=z;this.members.addAll(members);}
    }
}
