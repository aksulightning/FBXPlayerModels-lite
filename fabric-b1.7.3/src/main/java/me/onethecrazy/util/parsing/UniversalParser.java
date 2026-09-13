package me.onethecrazy.util.parsing;

import me.onethecrazy.util.objects.SkinnedModel;
import me.onethecrazy.util.objects.Vertex;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class UniversalParser {
    private static final FBXParser fbxParser = new FBXParser();

    public static Optional<List<Vertex>> parse(Path path){
        var format = getParsingFormat(path);

        // If format == null we crash here
        // This could be due to:
        // - NotImplemented
        // - Outdated Client
        assert format != null;

        return parse(path, format);
    }

    public static Optional<List<Vertex>> parse(Path path, ParsingFormat format){
        return switch (format) {
            case FBX -> fbxParser.parse(path);
            default -> Optional.empty();
        };
    }

    public static Optional<SkinnedModel> parseSkinned(Path path, ParsingFormat format){
        return switch (format) {
            case FBX -> fbxParser.parseSkinned(path);
            default -> Optional.empty();
        };
    }

    public static ParsingFormat getParsingFormat(Path file){
        var fileName = file.getFileName().toString().toLowerCase();

        if(fileName.endsWith(".fbx"))
            return ParsingFormat.FBX;

        return null;
    }
}

