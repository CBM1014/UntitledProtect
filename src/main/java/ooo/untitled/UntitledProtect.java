package ooo.untitled;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.common.collect.Sets;

import lombok.SneakyThrows;

import static ooo.untitled.VersionLevel.modernApi;

import java.lang.reflect.Method;
import java.util.Set;

public class UntitledProtect extends JavaPlugin implements Listener {
    private static UntitledProtect singleton;
    
    public UntitledProtect() {
        singleton = this;
    }
    
    public static UntitledProtect instance() {
        return singleton;
    }
    
    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }
    
    // Cache the last source block so we can catch single updates
    private Block cachedSourceBlock;
    // The value can be from 0 to 5 (6 may appear instantly, see below),
    // each number represent an updated block around the source block.
    private int physicsIndex = 0;
    
    // Reflection constant and helper
    private final static Method IS_TICKED_METHOD = isTickedMethod();
    @SneakyThrows
    private static Method isTickedMethod() {
        Class<?> nmsChunkClass = Class.forName("net.minecraft.server.v1_" + (modernApi() ? "13" : "12") + "_R1.Chunk");
        return modernApi() ? nmsChunkClass.getMethod("v") : nmsChunkClass.getMethod("j"); // OBFHELPER - isTicked
    }
    private final static Method GET_HANDLE_METHOD = getHandleMethod();
    @SneakyThrows
    private static Method getHandleMethod() {
        Class<?> craftChunkClass = Class.forName("org.bukkit.craftbukkit.v1_" + (modernApi() ? "13" : "12") + "_R1.CraftChunk");
        Method getHandleMethod = craftChunkClass.getDeclaredMethod("getHandle");
        getHandleMethod.setAccessible(true);
        return getHandleMethod;
    }
    
    // Material generic constant
    private final static Material DOUBLE_PLANT = modernApi() ? Material.LEGACY_GRASS : Material.GRASS;
    private final static Material GRASS = modernApi() ? Material.LEGACY_GRASS : Material.GRASS;
    private final static Material DIRT = modernApi() ? Material.LEGACY_DIRT : Material.DIRT;
    private final static Material SOIL = modernApi() ? Material.LEGACY_SOIL : Material.SOIL;
    
    @SneakyThrows
    // Impl Note: Ignore the cancelleation will have a big chance to destroy our index,
    // a safe way to ignore cancalleation is pass a boolean value to the log method.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    /* 
     * Introduction to implementation of physics listening:
     * 2d view (above) of source block and its around blocks:
     *                       □  
     *                     □ ■ □
     *                       □  
     * 2d view (front) of source block and its around blocks:
     *                       □  
     *                     □ ■ □
     *                       □  
     * Actually, no difference but actually different view. 
     * When the source block (center, black one) changed in some way,
     * its around 6 blocks will be updated (i.e. apply physics) and
     * trigger a `BlockPhysicsEvent` for each around block, however,
     * the source block itself won't trigger a `BlockPhysicsEvent`.
     * All updates from the same source block should have same changed
     * type (the pervious type of source block).
     */
    public void onAnyApplyPhysics(BlockPhysicsEvent event) { // Confused with `any`, look at our custom events
        Block source = event.getSourceBlock();
        if (!(boolean) IS_TICKED_METHOD.invoke(GET_HANDLE_METHOD.invoke(source.getChunk())))
            return; // Only a workaround
        
        // Impl Note: Only plants will causing an event whose source block is the same on the triggered block.
        // By this way, the source block equals with the trigger block in reference.
        if (event.getBlock() == source) {
            Material previousType = event.getChangedType();
            // Impl Note: Double plant is one of the special block, we special case it because we
            // wanna capature the destroy of its upper plant which will not cause a physics event.
            // when break it by destroy its lower plant by hand, see below for more information.
            if (previousType != DOUBLE_PLANT)
                performLogBlockDisappearance(source, previousType);
            return;
        }
        
        if (cachedSourceBlock == null) {
            cachedSourceBlock = source; // No need to update every time
        } else {
            // A single update won't happen before a regular update finish on same source block
            if (cachedSourceBlock.getBlockKey() != source.getBlockKey()) {
                Bukkit.getLogger().warning("Skipped @"
                        + "from " + event.getChangedType().name() + " to " + source.getType().name() + " in " + source.getX() + ", " + source.getY() + ", " + source.getZ()); // TODO DEBUG
                // Impl Note: Natural water spread from terrain generated water source
                physicsIndex = 0; // We dunno whether current physics is dummy!
                cachedSourceBlock = source;
            }
        }
        
        // As you can see above, special blocks such as plant have been flitered,
        // so any event reach here was triggered by a `regular` update which update 6 face
        // blocks of the source block. However, we only wanna log once and capature the 6th
        // block is the simplest way.
        if (physicsIndex++ == 5) {
            if (!event.isCancelled()) {
                Material previousType = event.getChangedType();
                Bukkit.getLogger().severe("Logged @"
                        + "from " + event.getChangedType().name() + " to " + source.getType().name() + " in " + source.getX() + ", " + source.getY() + ", " + source.getZ()); // TODO DEBUG
                if (previousType == DOUBLE_PLANT && isLowerPlant(source)) {
                    // Workaround missing physics event for upper part of plant
                    // Impl Note: At this moment, if we check the type of upper block, we
                    // will only get `AIR`, so specify the type of the upper plant is needed.
                    performLogBlockDisappearance(source.getRelative(0, 1, 0), DOUBLE_PLANT);
                } else {
                    performLogBlockPhysics(source, previousType);
                }
            }
            // Impl Note: Reset is important otherwise we will only log the first and only one block.
            physicsIndex = 0;
            cachedSourceBlock = null; // No need to check when it's the first block of a physics round - we will ignore null, see above
        }
    }
    
    // Coding style: the performance of a `static` method is slightly lower than a `non-static` method, however,
    // a `static` word make things more clear, especially in multi-thread programming, so we choose it.
    // In the above assumption, we shouldn't access a static field in our method or there will be a mess.
    private static boolean isLowerPlant(Block plant) {
        // Impl Note: This method is more complex than its name, not only we use this to check whether the
        // block is the lower part of a double plant, but also indicate whether there is a upper part.
        // By this way, we can catch the missing physics event which you can find more information about above.
        Material groundType = plant.getRelative(0, -1, 0).getType();
        return groundType == GRASS || groundType == DIRT || groundType == SOIL;
    }
    
    private static void performLogBlockPhysics(Block block, Material previousType) {
        
    }
    
    private static void performLogBlockDisappearance(Block block /* get data from this in future */, Material previousType /* for double plants to manually specify type */) {
        
    }
}
