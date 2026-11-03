package net.poena.actors.core;

import com.google.gson.JsonObject;

/** What the control API can do. Implementations may be called from any thread and throw ApiException to refuse. */
public interface Control {
    JsonObject state();

    JsonObject spawn(JsonObject request);

    JsonObject despawn(JsonObject request);

    JsonObject act(JsonObject request);

    JsonObject scenePlay(JsonObject request);

    JsonObject sceneStop();

    JsonObject events(long since, int limit);

    /** One actor in detail: inventory, held slot, the open screen with its slots and the item on the cursor. */
    JsonObject actor(String name);

    /** How the server holds up: TPS, tick times, loaded chunks, entities, memory, actors. */
    JsonObject metrics();
}
