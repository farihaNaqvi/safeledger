-- Two separate databases: mock-bank-api owns bank data, bank-mcp-server owns its own
-- audit/pending-actions data. Keeping them apart means the MCP server's audit trail can't be
-- altered through the bank API's connection, and vice versa.
CREATE DATABASE safeledger_bank;
CREATE DATABASE safeledger_mcp;
