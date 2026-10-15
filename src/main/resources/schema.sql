CREATE TABLE IF NOT EXISTS app_users (
 id VARCHAR(40) PRIMARY KEY, username VARCHAR(40) NOT NULL UNIQUE,
 display_name VARCHAR(80) NOT NULL, password_hash VARCHAR(100) NOT NULL
);
CREATE TABLE IF NOT EXISTS spaces (
 id VARCHAR(40) PRIMARY KEY, name VARCHAR(100) NOT NULL, revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS memberships (
 user_id VARCHAR(40) NOT NULL REFERENCES app_users(id), space_id VARCHAR(40) NOT NULL REFERENCES spaces(id),
 PRIMARY KEY(user_id,space_id)
);
CREATE TABLE IF NOT EXISTS documents (
 id VARCHAR(40) PRIMARY KEY, space_id VARCHAR(40) NOT NULL REFERENCES spaces(id),
 filename VARCHAR(100) NOT NULL, version BIGINT NOT NULL,
 chunk_count INT NOT NULL, index_mode VARCHAR(12) NOT NULL, created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS document_space_idx ON documents(space_id);
CREATE TABLE IF NOT EXISTS chunks (
 id VARCHAR(100) PRIMARY KEY, document_id VARCHAR(40) NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
 filename VARCHAR(100) NOT NULL, page_number INT NOT NULL, line_start INT NOT NULL,
 line_end INT NOT NULL, text_content CLOB NOT NULL, vector_json CLOB NOT NULL
);
CREATE INDEX IF NOT EXISTS chunk_document_idx ON chunks(document_id);
CREATE TABLE IF NOT EXISTS conversations (
 id VARCHAR(40) PRIMARY KEY, space_id VARCHAR(40) NOT NULL REFERENCES spaces(id),
 user_id VARCHAR(40) NOT NULL REFERENCES app_users(id), title VARCHAR(100) NOT NULL, created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS conversation_owner_idx ON conversations(space_id,user_id);
CREATE TABLE IF NOT EXISTS turns (
 id VARCHAR(40) PRIMARY KEY, conversation_id VARCHAR(40) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
 sequence_number INT NOT NULL, question VARCHAR(2000) NOT NULL, answer CLOB NOT NULL,
 sources_json CLOB NOT NULL, trace_json CLOB NOT NULL, status VARCHAR(40) NOT NULL, created_at VARCHAR(40) NOT NULL,
 UNIQUE(conversation_id,sequence_number)
);
