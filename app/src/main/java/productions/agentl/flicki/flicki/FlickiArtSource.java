package productions.agentl.flicki.flicki;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;
import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.RemoteMuzeiArtSource;
import com.google.android.apps.muzei.api.UserCommand;
import productions.agentl.flicki.flicki.FlickiREST.Photo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;





public class FlickiArtSource extends RemoteMuzeiArtSource {

    private static final String TAG = FlickiArtSource.class.getSimpleName();
    private static final String SOURCE_NAME = "FlickiArtSource";

    private static final int ROTATE_TIME_MILLIS = 3 * 60 * 60 * 1000; // rotate every 3 hours
    private static final int COMMAND_SHARE_ARTWORK = 1337;

    public FlickiArtSource() {
        super(SOURCE_NAME);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setUserCommands(new UserCommand(BUILTIN_COMMAND_ID_NEXT_ARTWORK),
                new UserCommand(COMMAND_SHARE_ARTWORK, getString(R.string.share)));
    }

    @Override
    protected void onCustomCommand(int id) {
        if (id == COMMAND_SHARE_ARTWORK) {
            Artwork currentArtwork = getCurrentArtwork();
            if (currentArtwork == null) {
                Log.w(TAG, "No current artwork, can't share.");
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(FlickiArtSource.this,
                                R.string.source_error_no_artwork_to_share,
                                Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }

            String detailUrl = currentArtwork.getViewIntent().getDataString();
            String artist = currentArtwork.getByline().trim();

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "My Android wallpaper today is '"
                    + currentArtwork.getTitle().trim()
                    + "' by " + artist
                    + ". #Muzei\n\n"
                    + detailUrl);
            shareIntent = Intent.createChooser(shareIntent, "Share artwork");
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(shareIntent);
        }
        else {
            super.onCustomCommand(id);
        }
    }

    @Override
    protected void onTryUpdate(int reason) throws RetryException {
        String currentToken = (getCurrentArtwork() != null) ? getCurrentArtwork().getToken() : null;
        ArrayList<FlickiREST.Photo> photoList;

        // Try to load the persisted photos list first (our local cache)
        photoList = PhotosListLoader.readSavedPhotosListIfNotStale(this);

        if (photoList == null || photoList.isEmpty()) {
            final PhotosListLoader downloader = new PhotosListLoader();
            photoList = downloader.downloadPhotos();

            if (photoList == null) {
                Log.w(TAG, "Error retrieving the photos list.");

                if (downloader.getLastError() != null) {
                    // Not a network error, not a 5xx HTTP error: retry, but in a while
                    scheduleUpdate(ROTATE_TIME_MILLIS);
                    return;
                }
                throw new RetryException();
            }

            if (photoList.isEmpty()) {
                Log.w(TAG, "No photos returned from API.");
                scheduleUpdate(ROTATE_TIME_MILLIS);
                return;
            }

            // Cache the downloaded photos list
            PhotosListLoader.persistPhotosList(this, photoList);
        }
        else {
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "Using photos list from local cache. Entries in cache: " +
                        photoList.size());
            }
        }

        FlickiREST.Photo photo = selectRandomPhoto(currentToken, photoList);

        if (BuildConfig.DEBUG) Log.d(TAG, "Publishing artwork: " + photo);
        publishArtwork(new Artwork.Builder()
                .title(photo.getTitle())
                .byline("Romain Guy")
                .imageUri(Uri.parse(photo.getUrl_o()))
                .token(photo.getId())
                .viewIntent(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(
                                "http://www.flickr.com/photos/24046097%40N00/" + photo.getId())
                ))
                .build());

        scheduleUpdate(ROTATE_TIME_MILLIS);
    }

    private static FlickiREST.Photo selectRandomPhoto(String currentToken, List<FlickiREST.Photo> photoList) {
        Random random = new Random();
        FlickiREST.Photo photo;
        while (true) {
            photo = photoList.get(random.nextInt(photoList.size()));
            if (photoList.size() <= 1 || !TextUtils.equals(photo.getId(), currentToken)) {
                break;
            }
        }
        return photo;
    }

    private void scheduleUpdate(int delayMs) {
        Log.i(TAG, "Scheduling an update in " + delayMs + " ms");
        scheduleUpdate(System.currentTimeMillis() + delayMs);
    }
}
