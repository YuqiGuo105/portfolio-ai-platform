package site.yuqi.mcp.model;

/**
 * Mirror of the agent service's risk taxonomy. The gateway uses this to
 * decide whether confirmation is required + which audit fields to capture.
 */
public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
