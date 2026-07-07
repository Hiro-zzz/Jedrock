package com.jedrock.api.protocol;

/**
 * Supported protocol versions. 
 * Used for abstraction - actual packet ID mappings live in network/protocol implementations.
 */
public enum ProtocolVersion {

    // Java Edition
    JE_1_8(47, "1.8", false),
    JE_1_12_2(340, "1.12.2", false),

    // Bedrock / MCPE
    PE_1_1_5(113, "1.1.5", true);

    private final int protocol;
    private final String versionName;
    private final boolean bedrock;

    ProtocolVersion(int protocol, String versionName, boolean bedrock) {
        this.protocol = protocol;
        this.versionName = versionName;
        this.bedrock = bedrock;
    }

    public int getProtocolNumber() {
        return protocol;
    }

    public String getVersionName() {
        return versionName;
    }

    public boolean isBedrock() {
        return bedrock;
    }

    public boolean isJava() {
        return !bedrock;
    }

    public static ProtocolVersion fromProtocolNumber(int protocol) {
        for (ProtocolVersion v : values()) {
            if (v.protocol == protocol) return v;
        }
        return null;
    }
}
