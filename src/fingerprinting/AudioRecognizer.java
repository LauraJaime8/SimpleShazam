package fingerprinting;

import serialization.Serialization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import utilities.HashingFunctions;
import utilities.Spectrum;

public class AudioRecognizer {

    // The main hashtable required in our interpretation of the algorithm to
    // store the song repository
    private Map<Long, List<KeyPoint>> hashMapSongRepository;

    // Variable to stop/start the listening loop
    public boolean running;

    // Constructor
    public AudioRecognizer() {

        // Deserialize the hash table hashMapSongRepository (our song repository)
        this.hashMapSongRepository = Serialization.deserializeHashMap();
        this.running = true;
    }

    // Method used to acquire audio from the microphone and to add/match a song fragment
    public void listening(String songId, boolean isMatching) throws LineUnavailableException {

        // Fill AudioFormat with the recording we want for settings
        AudioFormat audioFormat = new AudioFormat(AudioParams.sampleRate,
                AudioParams.sampleSizeInBits, AudioParams.channels,
                AudioParams.signed, AudioParams.bigEndian);

        // Required to get audio directly from the microphone and process it as an 
        // InputStream (using TargetDataLine) in another thread      
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
        final TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(audioFormat);
        line.start();

        Thread listeningThread = new Thread(new Runnable() {

            @Override
            public void run() {
                // Output stream 
                ByteArrayOutputStream outStream = new ByteArrayOutputStream();
                // Reader buffer
                byte[] buffer = new byte[AudioParams.bufferSize];
                int n = 0;
                try {
                    while (running) {
                        // Reading
                        int count = line.read(buffer, 0, buffer.length);
                        // If buffer is not empty
                        if (count > 0) {
                            outStream.write(buffer, 0, count);
                        }
                    }

                    byte[] audioTimeDomain = outStream.toByteArray();

                    // Compute magnitude spectrum
                    double[][] magnitudeSpectrum = Spectrum.compute(audioTimeDomain);
                    // Determine the shazam action (add or matching) and perform it
                    shazamAction(magnitudeSpectrum, songId, isMatching);
                    // Close stream
                    outStream.close();
                    // Serialize again the hashMapSongRepository (our song repository)
                    Serialization.serializeHashMap(hashMapSongRepository);
                } catch (IOException e) {
                    System.err.println("I/O exception " + e);
                    System.exit(-1);
                }
            }
        });

        // Start listening
        listeningThread.start();

        System.out.println("Press ENTER key to stop listening...");
        try {
            System.in.read();
        } catch (IOException ex) {
            Logger.getLogger(AudioRecognizer.class.getName()).log(Level.SEVERE, null, ex);
        }
        this.running = false;
    }

    // Determine the shazam action (add or matching a song) and perform it 
    private void shazamAction(double[][] magnitudeSpectrum, String songId, boolean isMatching) {

        // Hash table used for matching (Map<songId, Map<offset,count>>)
        Map<String, Map<Integer, Integer>> matchMap
                = new HashMap<String, Map<Integer, Integer>>();

        // Iterate over all the chunks from the magnitude spectrum
        for (int c = 0; c < magnitudeSpectrum.length; c++) {
            // Compute the hash entry for the current chunk (magnitudeSpectrum[c])

            //----------------------------------
            long hashEntry = computeHashEntry(magnitudeSpectrum[c]);
            //----------------------------------     

            //Si añado una cancion nueva
            if (!isMatching) {
                // Adding keypoint to the list in its relative hash entry which has been computed before

                //--------------------------------------------
                //Creo un nuevo punto y una lista de puntos y añado los puntos que se van recogiendo a la lista
                KeyPoint point = new KeyPoint(songId, c);
                List<KeyPoint> listPoints = null;
                listPoints = new ArrayList<KeyPoint>();
                listPoints.add(point);
                //Incluyo en el repositorio de canciones la entrada y la lista de puntos
                hashMapSongRepository.put(hashEntry, listPoints);
                //--------------------------------------------

            } // In the case of matching a song fragment
            //En el caso de que si exista un fragmento de la cancion en el repositorio
            else {
                // Iterate over the list of keypoints that matches the hash entry
                // in the the current chunk
                // For each keypoint:
                // Compute the time offset (Math.abs(point.getTimestamp() - c))
                //--------------------------------------------
                //Iteramos en la lista de puntos
                List<KeyPoint> listPoints;
                if ((listPoints = hashMapSongRepository.get(hashEntry)) != null) {
                    for (KeyPoint point : listPoints) {
                        //Recogemos el offset de cada punto
                        int offset = Math.abs(point.getTimestamp() - c);
                        // Now, focus on the matchMap hashtable:
                        // If songId (extracted from the current keypoint) has not been found yet in the matchMap add it                    
                        Map<Integer, Integer> tempMap;
                        //Si el id de la cancion no se ha encontrado en matchMap
                        if ((tempMap = matchMap.get(point.getSongId())) == null) {
                            tempMap = new HashMap<Integer, Integer>();
                            //Se añade el offset y la cuenta de lo añadido (1 es count)
                            //Map<songId, Map<offset,count>>
                            tempMap.put(offset, 1);
                            //Se añade a matchMp el nuevo id y tempMap con el offset y la cuenta
                            matchMap.put(point.getSongId(), tempMap);
                        } else {
                            //Si el id de la cancion ya existe y es la primera vez que se calcula es offset para ese id
                            Integer count = tempMap.get(offset);
                            if (count == null) {
                                tempMap.put(offset, new Integer(1));
                            } else {
                                tempMap.put(offset, new Integer(count + 1));
                            }
                        }
                    }
                }
                //--------------------------------------------
            }
        } // End iterating over the chunks/ventanas of the magnitude spectrum
        // If we chose matching, we 
        if (isMatching) {
            showBestMatching(matchMap);
        }
    }

    // Find out in which range the frequency is
    private int getIndex(int freq) {

        int i = 0;
        while (AudioParams.range[i] < freq) {
            i++;
        }
        return i;
    }

    // Compute hash entry for the chunk/ventana spectra 
    private long computeHashEntry(double[] chunk) {

        // Variables to determine the hash entry for this chunk/window spectra
        double highscores[] = new double[AudioParams.range.length];
        int frequencyPoints[] = new int[AudioParams.range.length];

        for (int freq = AudioParams.lowerLimit; freq < AudioParams.unpperLimit - 1; freq++) {
            // Get the magnitude
            double mag = chunk[freq];
            // Find out which range we are in
            int index = getIndex(freq);
            // Save the highest magnitude and corresponding frequency:
            if (mag > highscores[index]) {
                highscores[index] = mag;
                frequencyPoints[index] = freq;
            }
        }
        // Hash function 
        return HashingFunctions.hash1(frequencyPoints[0], frequencyPoints[1],
                frequencyPoints[2], frequencyPoints[3], AudioParams.fuzzFactor);
    }

    // Method to find the songId with the most frequently/repeated time offset
    private void showBestMatching(Map<String, Map<Integer, Integer>> matchMap) {
        // Iterate over the songs in the hashtable used for matching (matchMap)
        //--------------------------------------------
        int bestOffset = 0;
        String bestSong = " ";

        // (For each song) Iterate over the nested hashtable Map<offset,count>
        //Para cada cancion se itera en el matchMap
        for (Entry<String, Map<Integer, Integer>> entryM : matchMap.entrySet()) {
            String id = entryM.getKey();
            int biggestOffset = 0;
            // Get the biggest offset for the current song and update (if necessary)
            // the best overall result found till the current iteration
            for (Map.Entry<Integer, Integer> entry : entryM.getValue().entrySet()) {
                if (entry.getValue() > biggestOffset) {
                    biggestOffset = entry.getValue();
                }

                if (biggestOffset > bestOffset) {
                    bestOffset = biggestOffset;
                    bestSong = id;
                }
            }
        }
        //--------------------------------------------
        
        // Print the songId string which represents the best matching     
        System.out.println("Best song: " + bestSong);
    }
}