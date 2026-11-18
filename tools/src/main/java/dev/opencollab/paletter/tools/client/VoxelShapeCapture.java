package dev.opencollab.paletter.tools.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.data.SerializableVoxelShape;
import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A captured voxel shape registry ({@code voxel_shapes.json} of a capture directory): the {@code VoxelShapesPacket} exactly as BDS sent it (shapes and
 * names in wire order, nothing derived). One shape per line, so a diff of two builds shows the shapes that changed.
 */
public final class VoxelShapeCapture {
    public static final String FILE = "voxel_shapes.json";
    private static final Gson GSON = new Gson();

    private VoxelShapeCapture() {
    }

    public static void write(Path file, VoxelShapesPacket packet, String bdsVersion) throws IOException {
        StringBuilder text = new StringBuilder("{\n");
        text.append("  \"_generated\": \"Captured from BDS by `capture` (:tools:run, also part of `update`); do not edit by hand\",\n");
        text.append("  \"bdsVersion\": ").append(GSON.toJson(bdsVersion)).append(",\n");
        text.append("  \"customShapeCount\": ").append(packet.getCustomShapeCount()).append(",\n");
        text.append("  \"shapes\": [");
        String separator = "\n";
        for (SerializableVoxelShape shape : packet.getShapes()) {
            JsonObject json = new JsonObject();
            json.addProperty("xSize", shape.getCells().getXSize());
            json.addProperty("ySize", shape.getCells().getYSize());
            json.addProperty("zSize", shape.getCells().getZSize());
            json.add("storage", GSON.toJsonTree(shape.getCells().getStorage()));
            json.add("xCoordinates", GSON.toJsonTree(shape.getXCoordinates()));
            json.add("yCoordinates", GSON.toJsonTree(shape.getYCoordinates()));
            json.add("zCoordinates", GSON.toJsonTree(shape.getZCoordinates()));
            text.append(separator).append("    ").append(GSON.toJson(json));
            separator = ",\n";
        }
        text.append("\n  ],\n  \"names\": {");
        separator = "\n";
        for (Map.Entry<String, Integer> name : packet.getNameMap().entrySet()) {
            text.append(separator).append("    ").append(GSON.toJson(name.getKey())).append(": ").append(name.getValue());
            separator = ",\n";
        }
        text.append("\n  }\n}\n");
        Files.writeString(file, text);
    }

    public static VoxelShapesPacket read(Path file) throws IOException {
        return packet(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
    }

    /** The packet of the parsed file. */
    public static VoxelShapesPacket packet(JsonObject root) {
        List<SerializableVoxelShape> shapes = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("shapes")) {
            shapes.add(shape(element.getAsJsonObject()));
        }
        Map<String, Integer> names = new LinkedHashMap<>();
        root.getAsJsonObject("names").entrySet().forEach(entry -> names.put(entry.getKey(), entry.getValue().getAsInt()));
        VoxelShapesPacket packet = new VoxelShapesPacket();
        packet.setShapes(shapes);
        packet.setNameMap(names);
        packet.setCustomShapeCount(root.get("customShapeCount").getAsInt());
        return packet;
    }

    /** One shape of the {@code shapes} list of the file. */
    public static SerializableVoxelShape shape(JsonObject json) {
        List<Short> storage = new ArrayList<>();
        json.getAsJsonArray("storage").forEach(value -> storage.add(value.getAsShort()));
        return new SerializableVoxelShape(
            new SerializableVoxelShape.SerializableCells(json.get("xSize").getAsShort(), json.get("ySize").getAsShort(),
                json.get("zSize").getAsShort(), storage),
            floats(json.getAsJsonArray("xCoordinates")), floats(json.getAsJsonArray("yCoordinates")),
            floats(json.getAsJsonArray("zCoordinates")));
    }

    private static List<Float> floats(JsonArray array) {
        List<Float> floats = new ArrayList<>();
        array.forEach(value -> floats.add(value.getAsFloat()));
        return floats;
    }
}
