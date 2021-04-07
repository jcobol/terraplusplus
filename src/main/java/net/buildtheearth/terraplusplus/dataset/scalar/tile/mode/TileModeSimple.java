package net.buildtheearth.terraplusplus.dataset.scalar.tile.mode;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;
import lombok.NonNull;
import net.daporkchop.lib.common.misc.string.PStrings;

/**
 * Simple tile format in which tile coordinates are simply used as-is.
 *
 * @author DaPorkchop_
 */
@JsonDeserialize
public class TileModeSimple implements TileMode {
    protected final String format;
    @Getter(onMethod_ = { @JsonGetter })
    protected final String extension;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public TileModeSimple(
            @JsonProperty(value = "extension", required = true) @NonNull String extension) {
        this.format = "%d/%d/%d." + extension;
        this.extension = extension;
    }

    @Override
    public String path(int tileX, int tileZ, int zoom) {
        return PStrings.fastFormat(this.format, zoom, tileX, tileZ);
    }
}
