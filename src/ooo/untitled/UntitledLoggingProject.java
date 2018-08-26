package ooo.untitled;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_12_R1.CraftChunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.common.collect.Sets;

public class UntitledLoggingProject extends JavaPlugin implements Listener {
    private static UntitledLoggingProject singleton;
    
    public UntitledLoggingProject() {
        singleton = this;
    }
    
    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }
    
    // The value can be from 0 to 5 (6 may appear instantly, see below),
    // each number represent an updated block around the source block.
    private int physicsIndex = 0;
    
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
	    if (!((CraftChunk) source.getChunk()).getHandle().j()) { // OBFHELPER - isTicked
	        Bukkit.getLogger().warning("Skipped physics from unpopulated chunk @ from " + event.getChangedType().name() + " to " + source.getType().name());
	        return;
	    }
	    
	    // Impl Note: Only plants will causing an event whose source block is the same on the triggered block.
	    // By this way, the source block equals with the trigger block in reference.
	    if (event.getBlock() == source) {
	        Material previousType = event.getChangedType();
	        // Impl Note: Double plant is one of the special block, we special case it because we
	        // wanna capature the destroy of its upper plant which will not cause a physics event.
	        // when break it by destroy its lower plant by hand, see below for more information.
	        if (previousType != Material.DOUBLE_PLANT)
	            performLogBlockDisappearance(source, previousType);
	        // Obviously no need to log those plant again.
	        return;
	    }
	    
	    // Multiple used below
	    Material changedType = event.getChangedType();
	    
	    // As you can see above, special blocks such as plant have been flitered,
	    // so any event reach here was triggered by a `regular` update which update 6 face
	    // blocks of the source block. However, we only wanna log once and capature the 6th
	    // block is the simplest way.
	    if (physicsIndex++ == 5) {
	        if (!event.isCancelled()) performLogBlockPhysics(source, changedType);
	        // Impl Note: Reset is important otherwise we will only log the first and only one block.
	        physicsIndex = 0;
	    }
    }
	
	// Coding style: the performance of a `static` method is slightly lower than a `non-static` method, however,
	// a `static` word make things more clear, especially in multi-thread programming, so we choose it.
	// In the above assumption, we shouldn't access a static field in our method or there will be a mess.
	private static boolean lowerPlant(Block plant) {
	    // Impl Note: This method is more complex than its name, not only we use this to check whether the
	    // block is the lower part of a double plant, but also indicate whether there is a upper part.
	    // By this way, we can catch the missing physics event which you can find more information about above.
	    Material groundType = plant.getRelative(0, -1, 0).getType();
	    return groundType == Material.GRASS || groundType == Material.DIRT || groundType == Material.SOIL;
	}
	
	private static void performLogBlockPhysics(Block block, Material previousType) {
	    // Impl Note: At this moment, if we check the type of upper block, we
	    // will only get `AIR`, so specify the type of the upper plant is needed.
        if (previousType == Material.DOUBLE_PLANT && lowerPlant(block))
            performLogBlockDisappearance(block.getRelative(0, 1, 0), Material.DOUBLE_PLANT);
        // Debug output for a regular (source) block change
     // Bukkit.getLogger().info("Changed block: " + block.getType().name() + ", pervious type: " + previousType.name());
	}
	
	private static void performLogBlockDisappearance(Block block /* get data from this in future */, Material previousType /* for double plants to manually specify type */) {
	    // Debug output for a disappeared block (single update - only plant and double plant for now)
	 // Bukkit.getLogger().warning("Disappeared block: " + previousType.name() + " (single)");
    }
	
	public static class Unsafe {
	    public static UntitledLoggingProject instance() {
	        return singleton;
	    }
	}
}
