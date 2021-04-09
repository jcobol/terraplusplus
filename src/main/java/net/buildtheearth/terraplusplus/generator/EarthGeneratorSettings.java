package net.buildtheearth.terraplusplus.generator;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Sets;
import io.github.opencubicchunks.cubicchunks.cubicgen.blue.endless.jankson.JsonGrammar;
import io.github.opencubicchunks.cubicchunks.cubicgen.blue.endless.jankson.api.DeserializationException;
import io.github.opencubicchunks.cubicchunks.cubicgen.blue.endless.jankson.api.SyntaxError;
import io.github.opencubicchunks.cubicchunks.cubicgen.customcubic.CustomGeneratorSettings;
import io.github.opencubicchunks.cubicchunks.cubicgen.preset.CustomGenSettingsSerialization;
import io.github.opencubicchunks.cubicchunks.cubicgen.preset.fixer.CustomGeneratorSettingsFixer;
import io.github.opencubicchunks.cubicchunks.cubicgen.preset.fixer.PresetLoadError;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.With;
import net.buildtheearth.terraplusplus.TerraMod;
import net.buildtheearth.terraplusplus.config.GlobalParseRegistries;
import net.buildtheearth.terraplusplus.generator.settings.GeneratorTerrainSettings;
import net.buildtheearth.terraplusplus.generator.settings.biome.GeneratorBiomeSettings;
import net.buildtheearth.terraplusplus.generator.settings.biome.GeneratorBiomeSettingsOverrides;
import net.buildtheearth.terraplusplus.generator.settings.biome.GeneratorBiomeSettingsTerra121;
import net.buildtheearth.terraplusplus.generator.settings.osm.GeneratorOSMSettings;
import net.buildtheearth.terraplusplus.generator.settings.osm.GeneratorOSMSettingsDefault;
import net.buildtheearth.terraplusplus.projection.GeographicProjection;
import net.buildtheearth.terraplusplus.projection.transform.FlipHorizontalProjectionTransform;
import net.buildtheearth.terraplusplus.projection.transform.FlipVerticalProjectionTransform;
import net.buildtheearth.terraplusplus.projection.transform.OffsetProjectionTransform;
import net.buildtheearth.terraplusplus.projection.transform.ProjectionTransform;
import net.buildtheearth.terraplusplus.projection.transform.ScaleProjectionTransform;
import net.buildtheearth.terraplusplus.projection.transform.SwapAxesProjectionTransform;
import net.buildtheearth.terraplusplus.util.TerraConstants;
import net.daporkchop.lib.binary.oio.StreamUtil;
import net.daporkchop.lib.common.ref.Ref;
import net.minecraftforge.event.terraingen.DecorateBiomeEvent;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static java.nio.file.StandardCopyOption.*;
import static java.nio.file.StandardOpenOption.*;
import static net.buildtheearth.terraplusplus.util.TerraConstants.*;
import static net.daporkchop.lib.common.util.PValidation.*;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@With
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class EarthGeneratorSettings {
    public static final int CONFIG_VERSION = 2;
    public static final String DEFAULT_SETTINGS;
    private static final LoadingCache<String, EarthGeneratorSettings> SETTINGS_PARSE_CACHE = CacheBuilder.newBuilder()
            .weakKeys().weakValues()
            .build(CacheLoader.from(EarthGeneratorSettings::parseUncached));
    public static final String BTE_DEFAULT_SETTINGS;

    static {
        try {
            try (InputStream in = EarthGeneratorSettings.class.getResourceAsStream("default_generator_settings.json5")) {
                DEFAULT_SETTINGS = new String(StreamUtil.toByteArray(in));
            }
            try (InputStream in = EarthGeneratorSettings.class.getResourceAsStream("bte_generator_settings.json5")) {
                BTE_DEFAULT_SETTINGS = new String(StreamUtil.toByteArray(in));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Parses the given generator settings.
     *
     * @param generatorSettings the settings string to parse
     * @return the parsed settings
     */
    public static EarthGeneratorSettings parse(String generatorSettings) {
        if (Strings.isNullOrEmpty(generatorSettings)) {
            generatorSettings = DEFAULT_SETTINGS;
        }

        return SETTINGS_PARSE_CACHE.getUnchecked(generatorSettings);
    }

    /**
     * Parses the given generator settings without caching.
     *
     * @param generatorSettings the settings string to parse
     * @return the parsed settings
     */
    @SneakyThrows(IOException.class)
    public static EarthGeneratorSettings parseUncached(String generatorSettings) {
        if (Strings.isNullOrEmpty(generatorSettings)) {
            generatorSettings = DEFAULT_SETTINGS;
        }

        if (!generatorSettings.contains("version")) { //upgrade legacy config
            TerraMod.LOGGER.info("Parsing legacy config: {}", generatorSettings);

            LegacyConfig legacy = TerraConstants.JSON_MAPPER.readValue(generatorSettings, LegacyConfig.class);

            GeographicProjection projection = TerraConstants.JSON_MAPPER.readValue("{\"" + LegacyConfig.upgradeLegacyProjectionName(legacy.projection) + "\":{}}", GeographicProjection.class);
            projection = LegacyConfig.orientProjectionLegacy(projection, legacy.orentation);
            if (legacy.scaleX != 1.0d || legacy.scaleY != 1.0d) {
                projection = new ScaleProjectionTransform(projection, legacy.scaleX, legacy.scaleY);
            }

            return new EarthGeneratorSettings(projection, legacy.customcubic, true, null, true, null, null, null, null, Collections.emptyList(), Collections.emptyList(), CONFIG_VERSION);
        }

        return TerraConstants.JSON_MAPPER.readValue(generatorSettings, EarthGeneratorSettings.class);
    }

    public static Path settingsFile(@NonNull Path worldDirectory) {
        return worldDirectory.resolve("data").resolve(MODID).resolve("generator_settings.json5");
    }

    @SneakyThrows(IOException.class)
    public static String readSettings(@NonNull Path settingsFile) {
        return new String(Files.readAllBytes(settingsFile), StandardCharsets.UTF_8);
    }

    @SneakyThrows(IOException.class)
    public static void writeSettings(@NonNull Path settingsFile, @NonNull String settings) {
        Files.createDirectories(settingsFile.getParent());
        Path tmpFile = settingsFile.resolveSibling(settingsFile.getFileName().toString() + ".tmp");
        Files.write(tmpFile, settings.getBytes(StandardCharsets.UTF_8), CREATE, TRUNCATE_EXISTING, WRITE, SYNC);
        Files.move(tmpFile, settingsFile, REPLACE_EXISTING, ATOMIC_MOVE);
    }

    @NonNull
    @Getter(onMethod_ = { @JsonGetter })
    protected final GeographicProjection projection;
    @NonNull
    @Getter(onMethod_ = { @JsonGetter })
    protected final String cwg;

    @Getter(onMethod_ = { @JsonGetter })
    protected final boolean useDefaultHeights;
    @Getter(onMethod_ = { @JsonGetter })
    protected final String[][] customHeights;
    @Getter(onMethod_ = { @JsonGetter })
    protected final boolean useDefaultTreeCover;
    @Getter(onMethod_ = { @JsonGetter })
    protected final String[][] customTreeCover;

    @Getter(onMethod_ = { @JsonGetter })
    protected final List<GeneratorBiomeSettings> biomeSettings;
    @Getter(onMethod_ = { @JsonGetter })
    protected final GeneratorOSMSettings osmSettings;
    @Getter(onMethod_ = { @JsonGetter })
    protected final GeneratorTerrainSettings terrainSettings;

    protected transient final Ref<EarthBiomeProvider> biomeProvider = Ref.soft(() -> new EarthBiomeProvider(this));
    protected transient final Ref<CustomGeneratorSettings> customCubic = Ref.soft(() -> {
        CustomGeneratorSettings cfg;
        if (this.cwg().isEmpty()) { //use new minimal defaults
            cfg = new CustomGeneratorSettings();
            cfg.mineshafts = cfg.caves = cfg.strongholds = cfg.dungeons = cfg.ravines = false;
            cfg.lakes.clear();
            cfg.waterLevel = 0;
        } else {
            try {
                cfg = CustomGenSettingsSerialization.jankson().fromJsonCarefully(this.cwg(), CustomGeneratorSettings.class);
            } catch (PresetLoadError | DeserializationException err) {
                throw new RuntimeException(err);
            } catch (SyntaxError err) {
                String message = err.getMessage() + '\n' + err.getLineMessage();
                throw new RuntimeException(message, err);
            }
        }
        return cfg;
    });
    protected transient final Ref<GeneratorDatasets> datasets = Ref.soft(() -> new GeneratorDatasets(this));

    @Getter(onMethod_ = { @JsonGetter })
    protected final Set<PopulateChunkEvent.Populate.EventType> skipChunkPopulation;
    @Getter(onMethod_ = { @JsonGetter })
    protected final Set<DecorateBiomeEvent.Decorate.EventType> skipBiomeDecoration;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public EarthGeneratorSettings(
            @JsonProperty(value = "projection", required = true) @NonNull GeographicProjection projection,
            @JsonProperty(value = "cwg") String cwg,
            @JsonProperty(value = "useDefaultHeights") Boolean useDefaultHeights,
            @JsonProperty(value = "customHeights") String[][] customHeights,
            @JsonProperty(value = "useDefaultTreeCover") @JsonAlias("useDefaultTrees") Boolean useDefaultTreeCover,
            @JsonProperty(value = "customTreeCover") String[][] customTreeCover,
            @JsonProperty(value = "biomeSettings") List<GeneratorBiomeSettings> biomeSettings,
            @JsonProperty(value = "osmSettings") GeneratorOSMSettings osmSettings,
            @JsonProperty(value = "terrainSettings") GeneratorTerrainSettings terrainSettings,
            @JsonProperty(value = "skipChunkPopulation") List<PopulateChunkEvent.Populate.EventType> skipChunkPopulation,
            @JsonProperty(value = "skipBiomeDecoration") List<DecorateBiomeEvent.Decorate.EventType> skipBiomeDecoration,
            @JsonProperty(value = "version", required = true) int version) {
        checkState(version == CONFIG_VERSION, "invalid version %d (expected: %d)", version, CONFIG_VERSION);

        this.projection = projection;
        this.cwg = Strings.isNullOrEmpty(cwg) ? "" : CustomGeneratorSettingsFixer.INSTANCE.fixJson(cwg).toJson(JsonGrammar.COMPACT);
        this.useDefaultHeights = useDefaultHeights != null ? useDefaultHeights : true;
        this.customHeights = customHeights != null ? customHeights : new String[0][];
        this.useDefaultTreeCover = useDefaultTreeCover != null ? useDefaultTreeCover : true;
        this.customTreeCover = customTreeCover != null ? customTreeCover : new String[0][];

        this.biomeSettings = biomeSettings != null ? biomeSettings : Arrays.asList(new GeneratorBiomeSettingsTerra121(), new GeneratorBiomeSettingsOverrides());
        this.osmSettings = osmSettings != null ? osmSettings : new GeneratorOSMSettingsDefault();
        this.terrainSettings = terrainSettings != null ? terrainSettings : GeneratorTerrainSettings.DEFAULT;

        this.skipChunkPopulation = skipChunkPopulation != null ? Sets.immutableEnumSet(skipChunkPopulation) : Sets.immutableEnumSet(PopulateChunkEvent.Populate.EventType.ICE);
        this.skipBiomeDecoration = skipBiomeDecoration != null ? Sets.immutableEnumSet(skipBiomeDecoration) : Sets.immutableEnumSet(DecorateBiomeEvent.Decorate.EventType.TREE);
    }

    @Override
    @SneakyThrows(JsonProcessingException.class)
    public String toString() {
        return TerraConstants.JSON_MAPPER.writeValueAsString(this);
    }

    @SneakyThrows(JsonProcessingException.class)
    public String toPrettyString() {
        return TerraConstants.JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
    }

    @JsonGetter("version")
    private int version() {
        return CONFIG_VERSION;
    }

    public EarthBiomeProvider biomeProvider() {
        return this.biomeProvider.get();
    }

    public CustomGeneratorSettings customCubic() {
        return this.customCubic.get();
    }

    public GeneratorDatasets datasets() {
        return this.datasets.get();
    }

    /**
     * Tries to convert this generator settings to a String Terra121 could understand.
     *
     * @return a valid String representing this settings for Terra121, or null if not possible (e.g. using Terra++ features like offsets)
     * @author SmylerMC
     */
    @JsonIgnore
    @Deprecated
    public String getLegacyGeneratorString() {
        LegacyConfig legacy = new LegacyConfig();
        legacy.scaleX = 1;
        legacy.scaleY = 1;
        GeographicProjection proj = this.projection();
        GeographicProjection base = proj;
        ProjectionTransform transform = null;

        while (base instanceof ProjectionTransform) {
            base = ((ProjectionTransform) base).delegate();
        }

        legacy.projection = LegacyConfig.downgradeToLegacyProjectionName(GlobalParseRegistries.PROJECTIONS.inverse().get(base.getClass()));
        if (legacy.projection == null) {
            return null;
        }

        while (proj instanceof ProjectionTransform) {
            ProjectionTransform trs = (ProjectionTransform) proj;
            if (proj instanceof ScaleProjectionTransform) {
                ScaleProjectionTransform scale = (ScaleProjectionTransform) proj;
                legacy.scaleX *= scale.x();
                legacy.scaleY *= scale.y();
            } else if (proj instanceof FlipVerticalProjectionTransform || proj instanceof SwapAxesProjectionTransform) {
                if (transform != null) {
                    return null; // Terra121 does not support multiple transformations
                }
                transform = trs;
            } else if (proj instanceof FlipHorizontalProjectionTransform || proj instanceof OffsetProjectionTransform) {
                return null; // Terra121 does not support horizontal flips and offsets
            }
            proj = trs.delegate();
        }

        if (base.upright()) {
            if (transform == null) {
                legacy.orentation = LegacyConfig.Orientation.upright;
            } else if (transform instanceof FlipVerticalProjectionTransform) {
                legacy.orentation = LegacyConfig.Orientation.none; // Terra121 will flip it anyway because it's upright
            } else if (transform instanceof SwapAxesProjectionTransform) {
                return null; // There is one case we are not handling here, that of an upright, swapped and flipped vertically projection
            }
        } else {
            if (transform == null) {
                legacy.orentation = LegacyConfig.Orientation.none;
            } else if (transform instanceof FlipVerticalProjectionTransform) {
                legacy.orentation = LegacyConfig.Orientation.upright;
            } else if (transform instanceof SwapAxesProjectionTransform) {
                legacy.orentation = LegacyConfig.Orientation.swapped;
            }
        }
        try {
            return TerraConstants.JSON_MAPPER.writeValueAsString(legacy);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static class LegacyConfig {
        private static GeographicProjection orientProjectionLegacy(GeographicProjection base, Orientation orientation) {
            if (base.upright()) {
                if (orientation == Orientation.upright) {
                    return base;
                }
                base = new FlipVerticalProjectionTransform(base);
            }

            if (orientation == Orientation.swapped) {
                return new SwapAxesProjectionTransform(base);
            } else if (orientation == Orientation.upright) {
                base = new FlipVerticalProjectionTransform(base);
            }

            return base;
        }

        private static String upgradeLegacyProjectionName(String name) {
            switch (name) {
                case "web_mercator":
                    return "centered_mercator";
                case "airocean":
                    return "dymaxion";
                case "conformal":
                    return "conformal_dymaxion";
                case "bteairocean":
                    return "bte_conformal_dymaxion";
                default:
                    return name;
            }
        }

        private static String downgradeToLegacyProjectionName(String name) {
            switch (name) {
                case "centered_mercator":
                    return "web_mercator";
                case "dymaxion":
                    return "airocean";
                case "conformal_dymaxion":
                    return "conformal";
                case "bte_conformal_dymaxion":
                    return "bteairocean";
                case "equirectangular":
                case "sinusoidal":
                case "equal_earth":
                case "transverse_mercator":
                    return name;
                default:
                    return null;
            }
        }

        public String projection = "equirectangular";
        public Orientation orentation = Orientation.none;
        public double scaleX = 100000.0d;
        public double scaleY = 100000.0d;
        public String customcubic = "";

        @JsonAnySetter
        private void fallback(String key, String value) {
            TerraMod.LOGGER.warn("Ignoring unknown legacy config option: \"{}\": \"{}\"", key, value);
        }

        private enum Orientation {
            none, upright, swapped
        }
    }
}