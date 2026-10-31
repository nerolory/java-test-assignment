package core.security;

import core.contracts.SecretStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;

/** Файл с секретом до 4096 байт, защищённым Windows DPAPI для текущей учётной записи ОС. */
public final class WindowsSecretStore implements SecretStore {
    /** Файл с зашифрованными данными. */
    private final Path file;

    /**
     * Запоминает путь без обращения к диску.
     * @param file путь к файлу секрета
     */
    public WindowsSecretStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<byte[]> read() throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        if (Files.size(file) > 8192) {
            throw new IOException("Invalid protected secret size");
        }
        return Optional.of(transform(Files.readAllBytes(file), false));
    }

    /** {@inheritDoc} */
    @Override
    public void write(byte[] secret) throws IOException {
        byte[] protectedBytes = transform(secret, true);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "session-", ".tmp");
        try {
            Files.write(temporary, protectedBytes);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void delete() throws IOException {
        Files.deleteIfExists(file);
    }

    /**
     * Вызывает DPAPI через системный PowerShell. Секрет передаётся только через stdin.
     * @param bytes исходные байты
     * @param protect true для шифрования
     * @return преобразованные байты
     * @throws IOException если DPAPI недоступен или операция завершилась ошибкой
     */
    private static byte[] transform(byte[] bytes, boolean protect) throws IOException {
        if (bytes.length > (protect ? 4096 : 8192)) {
            throw new IOException("Secret is too large");
        }
        String operation = protect ? "Protect" : "Unprotect";
        String script = "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Security; "
                + "$data=[Convert]::FromBase64String([Console]::In.ReadToEnd()); "
                + "$result=[System.Security.Cryptography.ProtectedData]::" + operation
                + "($data,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser); "
                + "[Console]::Out.Write([Convert]::ToBase64String($result))";
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] input = Base64.getEncoder().encode(bytes);
        byte[] output = null;
        // Читаем stdout одновременно с записью stdin, чтобы не заполнить буфер процесса.
        FutureTask<byte[]> reader = new FutureTask<>(() -> process.getInputStream().readNBytes(16385));
        Thread.ofVirtual().start(reader);
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input);
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("DPAPI operation timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("DPAPI operation failed for this Windows account");
            }
            output = reader.get(1, TimeUnit.SECONDS);
            if (output.length > 16384) {
                throw new IOException("Invalid DPAPI response size");
            }
            return Base64.getDecoder().decode(output);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("DPAPI operation interrupted", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid DPAPI response", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new IOException("Could not read DPAPI response", error);
        } finally {
            Arrays.fill(input, (byte) 0);
            if (output != null) {
                Arrays.fill(output, (byte) 0);
            }
            process.destroyForcibly();
            reader.cancel(true);
            process.getInputStream().close();
        }
    }
}
