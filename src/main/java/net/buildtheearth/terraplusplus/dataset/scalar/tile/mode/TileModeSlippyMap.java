package net.buildtheearth.terraplusplus.dataset.scalar.tile.mode;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.NonNull;
import net.daporkchop.lib.common.misc.string.PStrings;

/**
 * The OpenStreetMap "Slippy Map" tile format.
 *
 * @author DaPorkchop_
 */
@JsonDeserialize
public class TileModeSlippyMap implements TileMode {
    protected final String format;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public TileModeSlippyMap(
            @JsonProperty(value = "extension", required = true) @NonNull String extension) {
        this.format = "%d/%d/%d." + extension;
    }

    @Override
    public String path(int tileX, int tileZ, int zoom) {
        return PStrings.fastFormat(this.format, zoom, tileX, ((1 << zoom) - 1) - tileZ);
    }
}
