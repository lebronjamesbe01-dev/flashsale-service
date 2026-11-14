-- Argon2id hashes with the {argon2} prefix are longer than the 60-char BCrypt ones.
ALTER TABLE users ALTER COLUMN password_hash TYPE VARCHAR(255);
