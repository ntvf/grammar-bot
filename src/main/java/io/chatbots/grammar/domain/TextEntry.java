package io.chatbots.grammar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** One text the user sent, together with the latest result shown for it. */
@Entity
@Table(name = "text_entries")
public class TextEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_user_id", nullable = false)
    private ChatUser chatUser;

    @Column(name = "source_text", nullable = false, columnDefinition = "TEXT")
    private String sourceText;

    @Column(name = "source_message_id")
    private Integer sourceMessageId;

    @Column(name = "result_message_id")
    private Integer resultMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_language", nullable = false, length = 8)
    private Language targetLanguage;

    @Enumerated(EnumType.STRING)
    @Column(name = "tone", nullable = false, length = 16)
    private Tone tone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TextStatus status = TextStatus.PENDING;

    @Column(name = "result_text", columnDefinition = "TEXT")
    private String resultText;

    @Column(name = "detected_language", length = 8)
    private String detectedLanguage;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 16)
    private TextAction action;

    @Column(name = "changes_json", columnDefinition = "TEXT")
    private String changesJson;

    @Column(name = "explanation_shown", nullable = false)
    private boolean explanationShown;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public ChatUser getChatUser() {
        return chatUser;
    }

    public void setChatUser(ChatUser chatUser) {
        this.chatUser = chatUser;
    }

    public String getSourceText() {
        return sourceText;
    }

    public void setSourceText(String sourceText) {
        this.sourceText = sourceText;
    }

    public Integer getSourceMessageId() {
        return sourceMessageId;
    }

    public void setSourceMessageId(Integer sourceMessageId) {
        this.sourceMessageId = sourceMessageId;
    }

    public Integer getResultMessageId() {
        return resultMessageId;
    }

    public void setResultMessageId(Integer resultMessageId) {
        this.resultMessageId = resultMessageId;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Language getTargetLanguage() {
        return targetLanguage;
    }

    public void setTargetLanguage(Language targetLanguage) {
        this.targetLanguage = targetLanguage;
    }

    public Tone getTone() {
        return tone;
    }

    public void setTone(Tone tone) {
        this.tone = tone;
    }

    public TextStatus getStatus() {
        return status;
    }

    public void setStatus(TextStatus status) {
        this.status = status;
    }

    public String getResultText() {
        return resultText;
    }

    public void setResultText(String resultText) {
        this.resultText = resultText;
    }

    public String getDetectedLanguage() {
        return detectedLanguage;
    }

    public void setDetectedLanguage(String detectedLanguage) {
        this.detectedLanguage = detectedLanguage;
    }

    public TextAction getAction() {
        return action;
    }

    public void setAction(TextAction action) {
        this.action = action;
    }

    public String getChangesJson() {
        return changesJson;
    }

    public void setChangesJson(String changesJson) {
        this.changesJson = changesJson;
    }

    public boolean isExplanationShown() {
        return explanationShown;
    }

    public void setExplanationShown(boolean explanationShown) {
        this.explanationShown = explanationShown;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
