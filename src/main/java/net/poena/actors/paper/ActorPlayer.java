package net.poena.actors.paper;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** A ServerPlayer driven by the plugin instead of a client. */
final class ActorPlayer extends ServerPlayer {
    ActorPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
        super(server, level, profile, info);
    }

    /** One tick of intent: forward and strafe are -1..1. LivingEntity.aiStep turns them into movement. */
    void drive(float forward, float strafe, boolean jump, boolean sprint) {
        this.zza = forward;
        this.xxa = strafe;
        this.setJumping(jump);
        this.setSprinting(sprint && forward > 0);
    }
}
