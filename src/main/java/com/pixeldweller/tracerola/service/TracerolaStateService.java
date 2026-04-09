package com.pixeldweller.tracerola.service;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.pixeldweller.tracerola.model.TraceSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Project-scoped service that holds the history of {@link TraceSession trace sessions}
 * produced during the current IDE session, together with the generated source code
 * for each one.
 *
 * <p>The tool window and dialogs subscribe via {@link #addListener} to refresh
 * themselves whenever new sessions arrive.
 */
@Service(Service.Level.PROJECT)
public final class TracerolaStateService {

    /** Maximum number of sessions kept in memory. */
    private static final int MAX_SESSIONS = 50;

    private final Project project;
    private final List<TraceSession> sessions = new CopyOnWriteArrayList<>();
    private final Map<TraceSession, String> generatedCode = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Current tracing status message, or {@code null} when idle. */
    private volatile String tracingStatus;
    private final List<Runnable> tracingListeners = new CopyOnWriteArrayList<>();

    public TracerolaStateService(Project project) {
        this.project = project;
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    /** Stores a new trace session and its generated source code, then notifies listeners. */
    public void addSession(@NotNull TraceSession session, @NotNull String code) {
        sessions.add(0, session); // newest first
        if (sessions.size() > MAX_SESSIONS) {
            TraceSession evicted = sessions.remove(sessions.size() - 1);
            generatedCode.remove(evicted);
        }
        generatedCode.put(session, code);
        notifyListeners();
    }

    /** Returns all stored sessions, newest first, as an unmodifiable view. */
    @NotNull
    public List<TraceSession> getSessions() {
        return Collections.unmodifiableList(sessions);
    }

    /** Returns the generated source code associated with {@code session}, or {@code null}. */
    @Nullable
    public String getGeneratedCode(@NotNull TraceSession session) {
        return generatedCode.get(session);
    }

    // -------------------------------------------------------------------------
    // Listener management
    // -------------------------------------------------------------------------

    public void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Runnable listener) {
        listeners.remove(listener);
    }

    // -------------------------------------------------------------------------
    // Tracing state
    // -------------------------------------------------------------------------

    /** Returns {@code true} when a trace is in progress. */
    public boolean isTracing() {
        return tracingStatus != null;
    }

    /** Returns the current status message, or {@code null} when idle. */
    @Nullable
    public String getTracingStatus() {
        return tracingStatus;
    }

    /** Sets the tracing status message and notifies listeners. Pass {@code null} to clear. */
    public void setTracingStatus(@Nullable String status) {
        this.tracingStatus = status;
        for (Runnable r : tracingListeners) {
            r.run();
        }
    }

    public void addTracingListener(@NotNull Runnable listener) {
        tracingListeners.add(listener);
    }

    public void removeTracingListener(@NotNull Runnable listener) {
        tracingListeners.remove(listener);
    }

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

    public void notify(@NotNull String message, @NotNull NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("TrAcerola Notifications")
                .createNotification("TrAcerola", message, type)
                .notify(project);
    }

    // -------------------------------------------------------------------------

    private void notifyListeners() {
        for (Runnable r : listeners) {
            r.run();
        }
    }
}
