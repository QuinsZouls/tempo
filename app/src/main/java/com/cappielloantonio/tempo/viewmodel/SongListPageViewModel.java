package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.repository.ArtistRepository;
import com.cappielloantonio.tempo.repository.SongRepository;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Genre;
import com.cappielloantonio.tempo.util.Constants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SongListPageViewModel extends AndroidViewModel {
    private final SongRepository songRepository;
    private final ArtistRepository artistRepository;

    public String title;
    public String toolbarTitle;
    public Genre genre;
    public ArtistID3 artist;
    public AlbumID3 album;

    private MutableLiveData<List<Child>> songList;

    private boolean hasMoreAllSongs = false;
    private int allSongsServerOffset = 0;

    public ArrayList<String> filters = new ArrayList<>();
    public ArrayList<String> filterNames = new ArrayList<>();

    public int year = 0;
    public int maxNumberByYear = 500;
    public int maxNumberByGenre = 100;

    public SongListPageViewModel(@NonNull Application application) {
        super(application);

        songRepository = new SongRepository();
        artistRepository = new ArtistRepository();
    }

    public LiveData<List<Child>> getSongList() {
        songList = new MutableLiveData<>(new ArrayList<>());

        switch (title) {
            case Constants.MEDIA_BY_GENRE:
                songList = songRepository.getSongsByGenre(genre.getGenre(), 0);
                break;
            case Constants.MEDIA_BY_ARTIST:
                songList = artistRepository.getTopSongs(artist.getName(), 50);
                break;
            case Constants.MEDIA_BY_GENRES:
                songList = songRepository.getSongsByGenres(filters);
                break;
            case Constants.MEDIA_BY_YEAR:
                songList = songRepository.getRandomSample(maxNumberByYear, year, year + 10);
                break;
            case Constants.MEDIA_STARRED:
                songList = songRepository.getStarredSongs(false, -1);
                break;
            case Constants.MEDIA_ALL_SONGS:
                allSongsServerOffset = 0;
                hasMoreAllSongs = true;
                songList = songRepository.getAllSongs(0);
                break;
        }

        return songList;
    }

    public void getSongsByPage(LifecycleOwner owner) {
        switch (title) {
            case Constants.MEDIA_BY_GENRE:
                int songCount = songList.getValue() != null ? songList.getValue().size() : 0;

                if (songCount > 0 && songCount % maxNumberByGenre != 0) return;

                int page = songCount / maxNumberByGenre;
                songRepository.getSongsByGenre(genre.getGenre(), page).observe(owner, children -> {
                    if (children != null && !children.isEmpty()) {
                        List<Child> currentMedia = songList.getValue();
                        currentMedia.addAll(children);
                        songList.setValue(currentMedia);
                    }
                });
                break;
            case Constants.MEDIA_ALL_SONGS:
                if (songList == null || !hasMoreAllSongs) return;

                List<Child> loadedAllSongs = songList.getValue();

                // The first page is requested by getSongList(), so the server offset is unknown until
                // the first scroll: seed it with what has been loaded so far. From then on it advances
                // by the number of rows actually returned, not by the (deduplicated) list size, so
                // overlapping pages cannot make it under-shoot and skip songs.
                if (allSongsServerOffset == 0) {
                    allSongsServerOffset = loadedAllSongs != null ? loadedAllSongs.size() : 0;
                }

                int allSongsOffset = allSongsServerOffset;

                songRepository.getAllSongs(allSongsOffset).observe(owner, children -> {
                    List<Child> currentMedia = songList.getValue();
                    List<Child> merged = new ArrayList<>(currentMedia != null ? currentMedia : new ArrayList<>());

                    if (children == null) {
                        // The request failed: publish the list unchanged so the fragment releases its
                        // isLoading flag, and leave hasMoreAllSongs untouched so a later scroll retries.
                        songList.setValue(merged);
                        return;
                    }

                    Set<String> knownIds = new HashSet<>();
                    for (Child child : merged) {
                        if (child.getId() != null) knownIds.add(child.getId());
                    }

                    int added = 0;

                    for (Child child : children) {
                        if (child.getId() == null || knownIds.add(child.getId())) {
                            merged.add(child);
                            added++;
                        }
                    }

                    allSongsServerOffset = allSongsOffset + children.size();
                    hasMoreAllSongs = added > 0;

                    songList.setValue(merged);
                });
                break;
            case Constants.MEDIA_BY_ARTIST:
            case Constants.MEDIA_BY_GENRES:
            case Constants.MEDIA_BY_YEAR:
            case Constants.MEDIA_STARRED:
                break;
        }
    }

    public List<Child> getCurrentSongList() {
        return songList != null ? songList.getValue() : null;
    }

    public boolean hasMoreAllSongs() {
        return hasMoreAllSongs;
    }

    public String getFiltersTitle() {
        return TextUtils.join(", ", filterNames);
    }
}
