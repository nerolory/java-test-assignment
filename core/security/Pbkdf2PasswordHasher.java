package core.security;

import core.contracts.PasswordHasher;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-HMAC-SHA256 из JDK: 600000 итераций, случайная соль 32 байта. */
public final class Pbkdf2PasswordHasher implements PasswordHasher {
    /** Число итераций новых хешей. */
    private static final int ITERATIONS = 600_000;
    /**
     * Минимум для сохранённых хешей. При повышении ITERATIONS не меняется,
     * иначе пользователи со старыми хешами не смогут войти.
     */
    private static final int MIN_ITERATIONS = 600_000;
    /** Источник соли. */
    private final SecureRandom random = new SecureRandom();

    /** Создаёт хешер с параметрами приложения. */
    public Pbkdf2PasswordHasher() { }

    /** {@inheritDoc} */
    @Override
    public String hash(char[] password) {
        Objects.requireNonNull(password);
        byte[] salt = new byte[32];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        try {
            return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                    + "$" + Base64.getEncoder().encodeToString(derived);
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean verify(char[] password, String encoded) {
        Objects.requireNonNull(password);
        if (encoded == null || encoded.length() > 256) {
            return false;
        }
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            // Предел исключает запуск произвольно дорогой операции из повреждённой БД.
            if (iterations < MIN_ITERATIONS || iterations > 2_000_000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length != 32 || expected.length != 32) {
                return false;
            }
            byte[] actual = derive(password, salt, iterations);
            try {
                return MessageDigest.isEqual(expected, actual);
            } finally {
                Arrays.fill(actual, (byte) 0);
            }
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    /**
     * Вычисляет ключ и очищает внутреннюю копию пароля.
     * @param password пароль
     * @param salt соль
     * @param iterations число итераций
     * @return ключ 256 бит
     */
    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Password hashing is unavailable", error);
        } finally {
            spec.clearPassword();
        }
    }
}
