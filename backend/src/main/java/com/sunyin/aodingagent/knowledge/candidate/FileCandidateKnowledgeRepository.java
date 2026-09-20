package com.sunyin.aodingagent.knowledge.candidate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateKnowledge;
import static com.sunyin.aodingagent.knowledge.candidate.CandidateKnowledgeModels.CandidateStatus;

@Repository
public class FileCandidateKnowledgeRepository implements CandidateKnowledgeRepository {

    private final Path directory;
    private final ObjectMapper objectMapper;

    @Autowired
    public FileCandidateKnowledgeRepository(
            @Value("${app.rag.candidate-directory:tmp/knowledge-candidates}") String directory,
            ObjectMapper objectMapper
    ) {
        this(Path.of(directory), objectMapper);
    }

    FileCandidateKnowledgeRepository(Path directory, ObjectMapper objectMapper) {
        this.directory = directory.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    @Override
    public CandidateKnowledge save(CandidateKnowledge candidate) {
        try {
            Files.createDirectories(directory);
            Path target = candidatePath(candidate.id());
            Path temporary = Files.createTempFile(directory, candidate.id() + "-", ".tmp");
            objectMapper.writeValue(temporary.toFile(), candidate);
            moveAtomically(temporary, target);
            return candidate;
        } catch (IOException exception) {
            throw new CandidateStorageException("候选知识保存失败", exception);
        }
    }

    @Override
    public Optional<CandidateKnowledge> findById(String id) {
        Path path = candidatePath(id);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), CandidateKnowledge.class));
        } catch (IOException exception) {
            throw new CandidateStorageException("候选知识读取失败", exception);
        }
    }

    @Override
    public List<CandidateKnowledge> findAll(CandidateStatus status) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::read)
                    .filter(candidate -> status == null || candidate.status() == status)
                    .sorted(Comparator.comparing(CandidateKnowledge::createdAt).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new CandidateStorageException("候选知识列表读取失败", exception);
        }
    }

    private CandidateKnowledge read(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), CandidateKnowledge.class);
        } catch (IOException exception) {
            throw new CandidateStorageException("候选知识读取失败", exception);
        }
    }

    private Path candidatePath(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("候选知识 ID 格式错误");
        }
        return directory.resolve(id + ".json");
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static class CandidateStorageException extends RuntimeException {
        CandidateStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
