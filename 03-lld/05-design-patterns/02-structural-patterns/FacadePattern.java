import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// =============================================================================
// PATTERN: Facade
// PURPOSE: Provide a single, simplified interface to a complex subsystem.
//          Clients interact with the Facade only — they never need to know
//          about the subsystem's internal structure or call sequence.
//
// REAL-WORLD ANALOGY:
//   Starting a modern car. You push one button. Under the hood: the ECU
//   checks fuel pressure, the starter motor engages, the fuel injectors
//   prime, spark timing adjusts, and the alternator kicks in. You don't
//   orchestrate any of that. The "start" button is the Facade — one simple
//   interface hiding a complex, ordered subsystem.
//
// THE PROBLEM THIS SOLVES:
//   Without a Facade, every client that wants to process a video must:
//   1. Know about 6 different subsystem classes
//   2. Know the exact order to call them
//   3. Know which data flows between which steps
//   4. Repeat this entire sequence in every caller
//   If the pipeline changes, every caller breaks.
//
//   With a Facade, the client calls ONE method. The Facade knows the
//   sequence. Only the Facade changes when the pipeline changes.
//
// THREE INGREDIENTS:
//   1. Subsystem classes  → do the real work, know nothing about the Facade
//   2. Facade             → knows all subsystem classes, provides simple API
//   3. Client             → only talks to the Facade
// =============================================================================

public class FacadePattern {

    // =========================================================================
    // THE SUBSYSTEM — complex internals the client should never deal with
    //
    // Each class is focused, well-designed, and reusable on its own.
    // The problem isn't the classes themselves — it's the orchestration
    // burden placed on every caller.
    //
    // KEY POINT: None of these classes know the Facade exists. The Facade
    // depends on the subsystem; the subsystem does NOT depend on the Facade.
    // =========================================================================

    // ── Subsystem Class 1: VideoValidator ─────────────────────────────────────
    static class VideoValidator {
        public ValidationResult validate(String filePath) {
            System.out.println("  [VideoValidator] Checking file: " + filePath);

            if (filePath == null || filePath.isEmpty()) {
                return new ValidationResult(false, "File path cannot be empty");
            }
            if (!filePath.endsWith(".mp4") && !filePath.endsWith(".mov")
                    && !filePath.endsWith(".avi")) {
                return new ValidationResult(false,
                        "Unsupported format. Allowed: mp4, mov, avi");
            }

            System.out.println("  [VideoValidator] ✓ File is valid");
            return new ValidationResult(true, "File validation passed");
        }
    }

    static class ValidationResult {
        final boolean valid;
        final String message;
        ValidationResult(boolean valid, String message) {
            this.valid   = valid;
            this.message = message;
        }
    }


    // ── Subsystem Class 2: MetadataExtractor ──────────────────────────────────
    static class MetadataExtractor {
        public VideoMetadata extract(String filePath) {
            System.out.println("  [MetadataExtractor] Extracting metadata from: " + filePath);
            // In reality: reads video headers, probes format
            VideoMetadata metadata = new VideoMetadata();
            metadata.put("duration",    "00:04:27");
            metadata.put("resolution",  "1920x1080");
            metadata.put("codec",       "h264");
            metadata.put("bitrate",     "5000kbps");
            metadata.put("frameRate",   "30fps");
            System.out.println("  [MetadataExtractor] ✓ Extracted: " + metadata);
            return metadata;
        }
    }

    // Thin wrapper around Map — gives the data a meaningful type name
    static class VideoMetadata extends HashMap<String, String> {
        @Override
        public String put(String key, String value) {
            return super.put(key, value);
        }
    }


    // ── Subsystem Class 3: VideoTranscoder ────────────────────────────────────
    static class VideoTranscoder {
        public String transcode(String inputPath, String targetFormat, VideoMetadata metadata) {
            System.out.println("  [VideoTranscoder] Transcoding: " + inputPath
                    + " → " + targetFormat
                    + " | resolution: " + metadata.get("resolution")
                    + " | codec: " + metadata.get("codec"));
            // Simulate time-consuming transcoding
            String outputFile = inputPath.replace(".", "_transcoded.") + "." + targetFormat;
            System.out.println("  [VideoTranscoder] ✓ Output: " + outputFile);
            return outputFile;
        }
    }


    // ── Subsystem Class 4: ThumbnailGenerator ─────────────────────────────────
    static class ThumbnailGenerator {
        public String generate(String videoPath, int timeOffsetSeconds) {
            System.out.println("  [ThumbnailGenerator] Generating thumbnail from: " + videoPath
                    + " at t=" + timeOffsetSeconds + "s");
            String thumbPath = videoPath.replace("transcoded", "thumb")
                    .replace(".mp4", ".jpg")
                    .replace(".webm", ".jpg")
                    .replace(".avi", ".jpg");
            System.out.println("  [ThumbnailGenerator] ✓ Thumbnail: " + thumbPath);
            return thumbPath;
        }
    }


    // ── Subsystem Class 5: VideoStorageService ────────────────────────────────
    static class VideoStorageService {
        private static final String CDN_BASE = "https://cdn.example.com/videos/";

        public StorageResult store(String transcodedPath, String thumbnailPath) {
            System.out.println("  [VideoStorage] Uploading video: " + transcodedPath);
            System.out.println("  [VideoStorage] Uploading thumbnail: " + thumbnailPath);

            String videoId  = UUID.randomUUID().toString().substring(0, 8);
            String videoUrl = CDN_BASE + videoId + "/video";
            String thumbUrl = CDN_BASE + videoId + "/thumb";

            System.out.println("  [VideoStorage] ✓ Stored at: " + videoUrl);
            return new StorageResult(videoId, videoUrl, thumbUrl);
        }
    }

    static class StorageResult {
        final String videoId;
        final String videoUrl;
        final String thumbnailUrl;
        StorageResult(String videoId, String videoUrl, String thumbnailUrl) {
            this.videoId      = videoId;
            this.videoUrl     = videoUrl;
            this.thumbnailUrl = thumbnailUrl;
        }
    }


    // ── Subsystem Class 6: NotificationService ────────────────────────────────
    static class NotificationService {
        public void notifyProcessingComplete(String videoUrl, VideoMetadata metadata,
                String[] subscriberEmails) {
            System.out.println("  [Notifications] Sending completion notifications to "
                    + subscriberEmails.length + " subscribers");
            System.out.println("  [Notifications] ✓ Video ready: " + videoUrl
                    + " | Duration: " + metadata.get("duration"));
        }

        public void notifyProcessingFailed(String filePath, String reason,
                String[] subscriberEmails) {
            System.out.println("  [Notifications] Sending failure notification");
            System.out.println("  [Notifications] ✗ Processing failed for: " + filePath
                    + " | Reason: " + reason);
        }
    }


    // ── Subsystem Class 7: AuditLogger ────────────────────────────────────────
    // Added later — the beauty of Facade is that clients don't feel this addition
    static class AuditLogger {
        public void logProcessingStart(String filePath, String requestedBy) {
            System.out.println("  [AuditLog] START | file=" + filePath
                    + " | by=" + requestedBy
                    + " | ts=" + System.currentTimeMillis());
        }

        public void logProcessingEnd(String videoUrl, boolean success) {
            System.out.println("  [AuditLog] END | url=" + videoUrl
                    + " | success=" + success
                    + " | ts=" + System.currentTimeMillis());
        }
    }


    // =========================================================================
    // RESULT CLASS — what the Facade returns to the client
    // Clean domain object, not a raw map or a subsystem-specific type
    // =========================================================================
    static class VideoProcessingResult {
        private final boolean success;
        private final String videoId;
        private final String videoUrl;
        private final String thumbnailUrl;
        private final String errorMessage;

        // Success constructor
        VideoProcessingResult(String videoId, String videoUrl, String thumbnailUrl) {
            this.success      = true;
            this.videoId      = videoId;
            this.videoUrl     = videoUrl;
            this.thumbnailUrl = thumbnailUrl;
            this.errorMessage = null;
        }

        // Failure constructor
        VideoProcessingResult(String errorMessage) {
            this.success      = false;
            this.videoId      = null;
            this.videoUrl     = null;
            this.thumbnailUrl = null;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess()       { return success; }
        public String getVideoId()       { return videoId; }
        public String getVideoUrl()      { return videoUrl; }
        public String getThumbnailUrl()  { return thumbnailUrl; }
        public String getErrorMessage()  { return errorMessage; }

        @Override
        public String toString() {
            if (success) {
                return "VideoProcessingResult{success=true, videoId='" + videoId
                        + "', url='" + videoUrl + "'}";
            }
            return "VideoProcessingResult{success=false, error='" + errorMessage + "'}";
        }
    }


    // =========================================================================
    // THE FACADE: VideoProcessingFacade
    //
    // This is the one class clients interact with. It:
    //   - Owns all subsystem instances (clients never instantiate them)
    //   - Knows the correct order to call them
    //   - Knows how data flows from one step to the next
    //   - Handles errors gracefully at each step
    //   - Returns a clean, simple result to the client
    //
    // When the pipeline changes (e.g., add virus scanning, add CDN replication),
    // ONLY this class changes. All clients are unaffected.
    // =========================================================================
    static class VideoProcessingFacade {

        // Facade owns the subsystem — clients never deal with these directly
        private final VideoValidator        validator;
        private final MetadataExtractor     metadataExtractor;
        private final VideoTranscoder       transcoder;
        private final ThumbnailGenerator    thumbnailGenerator;
        private final VideoStorageService   storageService;
        private final NotificationService   notificationService;
        private final AuditLogger           auditLogger;

        public VideoProcessingFacade() {
            this.validator           = new VideoValidator();
            this.metadataExtractor   = new MetadataExtractor();
            this.transcoder          = new VideoTranscoder();
            this.thumbnailGenerator  = new ThumbnailGenerator();
            this.storageService      = new VideoStorageService();
            this.notificationService = new NotificationService();
            this.auditLogger         = new AuditLogger();
        }

        // ─── The simplified API — ONE method, all the complexity hidden ───────
        //
        // Before this Facade existed, the client had to:
        //   1. Create a VideoValidator, call validate()
        //   2. Create a MetadataExtractor, call extract()
        //   3. Create a VideoTranscoder, call transcode() with metadata from step 2
        //   4. Create a ThumbnailGenerator, call generate() with output from step 3
        //   5. Create a VideoStorageService, call store() with outputs from steps 3 and 4
        //   6. Create a NotificationService, call notifyReady() with outputs from step 5
        //   7. Create an AuditLogger, call log methods at start and end
        //
        // Now the client just calls: facade.processVideo(filePath, format, uploader)
        public VideoProcessingResult processVideo(String filePath, String targetFormat,
                String uploadedBy, String[] notifyEmails) {

            System.out.println("  [Facade] ═══ Starting video processing pipeline ═══");
            System.out.println("  [Facade] File: " + filePath + " | Format: " + targetFormat);

            // Step 0: Audit log the start
            auditLogger.logProcessingStart(filePath, uploadedBy);

            // Step 1: Validate the input file
            ValidationResult validation = validator.validate(filePath);
            if (!validation.valid) {
                System.out.println("  [Facade] ✗ Validation failed: " + validation.message);
                notificationService.notifyProcessingFailed(filePath, validation.message, notifyEmails);
                auditLogger.logProcessingEnd("N/A", false);
                return new VideoProcessingResult("Validation failed: " + validation.message);
            }

            // Step 2: Extract metadata (needed by transcoder)
            VideoMetadata metadata = metadataExtractor.extract(filePath);

            // Step 3: Transcode the video (uses metadata from step 2)
            String transcodedPath = transcoder.transcode(filePath, targetFormat, metadata);

            // Step 4: Generate thumbnail (uses transcoded path from step 3)
            String thumbnailPath = thumbnailGenerator.generate(transcodedPath, 5);

            // Step 5: Store both video and thumbnail (uses outputs from steps 3 and 4)
            StorageResult stored = storageService.store(transcodedPath, thumbnailPath);

            // Step 6: Notify subscribers (uses URL and metadata from earlier steps)
            notificationService.notifyProcessingComplete(stored.videoUrl, metadata, notifyEmails);

            // Step 7: Audit log the completion
            auditLogger.logProcessingEnd(stored.videoUrl, true);

            System.out.println("  [Facade] ═══ Pipeline complete ═══");
            return new VideoProcessingResult(stored.videoId, stored.videoUrl, stored.thumbnailUrl);
        }

        // Additional simplified method — another entry point for a different use case
        public boolean isVideoValid(String filePath) {
            return validator.validate(filePath).valid;
        }
    }


    // =========================================================================
    // MAIN — shows the client-side simplicity vs the subsystem complexity
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Facade Pattern Demo ===\n");

        VideoProcessingFacade facade = new VideoProcessingFacade();

        // ── Example 1: Successful processing ──────────────────────────────────
        System.out.println("─── Case 1: Valid video file ────────────────────────────────────\n");

        // From the client's perspective: one method call.
        // All 7 subsystem classes and their coordination are invisible.
        VideoProcessingResult result = facade.processVideo(
                "software_design_lecture.mp4",
                "webm",
                "akash@arcesium.com",
                new String[]{"team@arcesium.com", "manager@arcesium.com"}
        );

        System.out.println("\n  Client received: " + result);


        // ── Example 2: Invalid file — Facade handles failure internally ───────
        System.out.println("\n─── Case 2: Invalid file type ──────────────────────────────────\n");

        VideoProcessingResult failResult = facade.processVideo(
                "document.pdf",            // wrong file type
                "mp4",
                "akash@arcesium.com",
                new String[]{"akash@arcesium.com"}
        );

        System.out.println("\n  Client received: " + failResult);


        // ── Example 3: Quick validation check via second facade method ─────────
        System.out.println("\n─── Case 3: Quick validation via Facade ─────────────────────────");
        System.out.println("  Is 'video.mp4' valid?  → " + facade.isVideoValid("video.mp4"));
        System.out.println("  Is 'file.exe' valid?   → " + facade.isVideoValid("file.exe"));


        // ── What the client would have had to write WITHOUT a Facade ──────────
        System.out.println("\n─── Without Facade (what clients would have to write) ───────────");
        System.out.println("""
  VideoValidator validator = new VideoValidator();
  ValidationResult v = validator.validate(filePath);
  if (!v.valid) { /* handle */ }

  MetadataExtractor extractor = new MetadataExtractor();
  VideoMetadata metadata = extractor.extract(filePath);

  VideoTranscoder transcoder = new VideoTranscoder();
  String transcodedPath = transcoder.transcode(filePath, format, metadata);

  ThumbnailGenerator thumbGen = new ThumbnailGenerator();
  String thumbPath = thumbGen.generate(transcodedPath, 5);

  VideoStorageService storage = new VideoStorageService();
  StorageResult stored = storage.store(transcodedPath, thumbPath);

  NotificationService notifier = new NotificationService();
  notifier.notifyProcessingComplete(stored.videoUrl, metadata, emails);

  AuditLogger logger = new AuditLogger();
  logger.logProcessingEnd(stored.videoUrl, true);

  → Every. Single. Caller. Would repeat this entire sequence.
  → Adding a new step (e.g., virus scan) would break ALL callers.""");


        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Facade hides 7 subsystem classes behind 1 method call");
        System.out.println("  2. Subsystem classes have NO knowledge of the Facade");
        System.out.println("  3. Adding steps to the pipeline = only the Facade changes");
        System.out.println("  4. Facade vs Adapter: Facade simplifies; Adapter translates");
        System.out.println("  5. Client code becomes trivially simple and focused on intent");
    }
}
