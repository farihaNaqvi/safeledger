package com.safeledger.bankmcpserver.identity;

import java.util.UUID;

/**
 * Who is calling, taken only from a verified identity header. Tools never accept a customer ID as
 * an argument; this is the only source of it.
 */
public record CallerIdentity(UUID customerId, String sessionId) {}
