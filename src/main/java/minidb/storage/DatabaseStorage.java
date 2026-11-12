package minidb.storage;

import minidb.model.TableData;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

public class DatabaseStorage {
    public static class State implements Serializable {
        private static final long serialVersionUID = 1L;
        public final Map<String, TableData> tables = new LinkedHashMap<String, TableData>();
    }

    private final Path file;

    public DatabaseStorage(Path file) {
        this.file = file;
    }

    public State load() throws IOException {
        if (!Files.exists(file)) return new State();
        try (ObjectInputStream input = new ObjectInputStream(new FileInputStream(file.toFile()))) {
            Object value = input.readObject();
            if (!(value instanceof State)) throw new IOException("Invalid database file");
            return (State) value;
        } catch (ClassNotFoundException e) {
            throw new IOException("Cannot read database file", e);
        }
    }

    public void save(State state) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (ObjectOutputStream output = new ObjectOutputStream(new FileOutputStream(temp.toFile()))) {
            output.writeObject(state);
            output.flush();
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
