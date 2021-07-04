//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                               A b s t r a c t C l a s s i f i e r                              //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2021. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.classifier;

import org.apache.commons.io.FileUtils;

import org.audiveris.omr.WellKnowns;
import static org.audiveris.omr.classifier.Classifier.SHAPE_COUNT;
import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.glyph.ShapeChecker;
import org.audiveris.omr.math.PoorManAlgebra.DataSet;
import org.audiveris.omr.math.PoorManAlgebra.INDArray;
import org.audiveris.omr.math.PoorManAlgebra.Nd4j;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.util.StopWatch;
import org.audiveris.omr.util.UriUtil;
import org.audiveris.omr.util.ZipFileSystem;

//import org.nd4j.linalg.api.ndarray.INDArray;
//import org.nd4j.linalg.dataset.DataSet;
//import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static java.nio.file.StandardOpenOption.CREATE;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import javax.xml.bind.JAXBException;

/**
 * Class {@code AbstractClassifier} is an abstract basis for all Classifier
 * implementations.
 * <p>
 * It handles the storing and loading of shape classifier model together with features norms
 * (means and standard deviations).
 * <p>
 * The classifier data is thus composed of two parts (model and norms) which are loaded as a whole
 * according to the following algorithm:
 * <ol>
 * <li>It first tries to find data in the application user local area ('train').
 * If found, this data contains a custom definition of model+norms, typically after a user
 * training.</li>
 * <li>If not found, it falls back reading the default definition from the application resource,
 * reading the 'res' folder in the application program area.
 * </ol>
 * <p>
 * After any user training, the data is stored as the custom definition in the user local area,
 * which will be picked up first when the application is run again.
 *
 * @param <M> precise model class to be used
 * @author Hervé Bitteur
 */
public abstract class AbstractClassifier<M extends Object>
        implements Classifier
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(AbstractClassifier.class);

    /** Entry name for mean values. */
    public static final String MEANS_ENTRY_NAME = "means.bin";

    /** Entry name for mean XML values. */
    public static final String MEANS_XML_ENTRY_NAME = "means.xml";

    /** Entry name for standard deviation values. */
    public static final String STDS_ENTRY_NAME = "stds.bin";

    /** Entry name for standard deviation XML values. */
    public static final String STDS_XML_ENTRY_NAME = "stds.xml";

    /** A special evaluation array, used to report NOISE. */
    private static final Evaluation[] noiseEvaluations = {new Evaluation(
        Shape.NOISE,
        Evaluation.ALGORITHM)};

    //~ Instance fields ----------------------------------------------------------------------------
    /** Features means and standard deviations. */
    protected Norms norms;

    /** Glyph features descriptor. */
    protected GlyphDescriptor descriptor;

    /** The glyph checker for additional specific checks. */
    protected ShapeChecker glyphChecker = ShapeChecker.getInstance();

    //~ Methods ------------------------------------------------------------------------------------
    //----------//
    // evaluate //
    //----------//
    @Override
    public Evaluation[] evaluate (Glyph glyph,
                                  SystemInfo system,
                                  int count,
                                  double minGrade,
                                  EnumSet<Classifier.Condition> conditions)
    {
        final int interline = system.getSheet().getInterline();

        return evaluate(glyph, system, count, minGrade, conditions, interline);
    }

    //----------//
    // evaluate //
    //----------//
    @Override
    public Evaluation[] evaluate (Glyph glyph,
                                  int interline,
                                  int count,
                                  double minGrade,
                                  EnumSet<Condition> conditions)
    {
        return evaluate(glyph, null, count, minGrade, conditions, interline);
    }

    //---------------//
    // getDescriptor //
    //---------------//
    @Override
    public GlyphDescriptor getGlyphDescriptor ()
    {
        return descriptor;
    }

    //---------------//
    // getRawDataSet //
    //---------------//
    /**
     * Build a raw (non normalized) dataset out of the provided collection of samples.
     *
     * @param samples the provided samples
     * @return a raw DataSet for use by a MultiLayerNetwork
     */
    public DataSet getRawDataSet (Collection<Sample> samples)
    {
        StopWatch watch = new StopWatch("getRawDataSet");
        watch.start("allocate doubles");

        final double[][] inputs = new double[samples.size()][];
        final double[][] desiredOutputs = new double[samples.size()][];
        int ig = 0;

        watch.start("browse samples");

        for (Sample sample : samples) {
            double[] ins = descriptor.getFeatures(sample, sample.getInterline());
            inputs[ig] = ins;

            double[] des = new double[SHAPE_COUNT];
            Arrays.fill(des, 0);
            des[sample.getShape().getPhysicalShape().ordinal()] = 1;
            desiredOutputs[ig] = des;

            ig++;
        }

        // Build the collection of features from the glyph data
        watch.start("features");

        final INDArray features = Nd4j.create(inputs);
        watch.start("labels");

        final INDArray labels = Nd4j.create(desiredOutputs);

        if (constants.printWatch.isSet()) {
            watch.print();
        }

        return new DataSet(features, labels, null, null);
    }

    //-------------//
    // isBigEnough //
    //-------------//
    @Override
    public boolean isBigEnough (Glyph glyph,
                                int interline)
    {
        return isBigEnough(glyph.getNormalizedWeight(interline));
    }

    //-------------//
    // isBigEnough //
    //-------------//
    @Override
    public boolean isBigEnough (double weight)
    {
        return weight >= constants.minWeight.getValue();
    }

    //----------------------//
    // getSortedEvaluations //
    //----------------------//
    /**
     * Run the classifier with the specified glyph, and return a sequence of all
     * interpretations (ordered from best to worst) with no additional check.
     *
     * @param glyph     the glyph to be examined
     * @param interline the global sheet interline
     * @return the ordered best evaluations
     */
    protected Evaluation[] getSortedEvaluations (Glyph glyph,
                                                 int interline)
    {
        // If too small, it's just NOISE
        if (!isBigEnough(glyph, interline)) {
            return noiseEvaluations;
        } else {
            Evaluation[] evals = getNaturalEvaluations(glyph, interline);
            Arrays.sort(evals, Evaluation.byReverseGrade); // Order the evals from best to worst

            return evals;
        }
    }

    //--------------//
    // isCompatible //
    //--------------//
    /**
     * Make sure the provided pair (model + norms) is compatible with the current
     * application version.
     *
     * @param model non-null model instance
     * @param norms non-null norms instance
     * @return true if engine is usable and found compatible
     */
    protected abstract boolean isCompatible (M model,
                                             Norms norms);

    //------//
    // load //
    //------//
    /**
     * Load model and norms from the most suitable classifier data files.
     * If user files do not exist or cannot be unmarshalled, the default files are used.
     *
     * @param fileName file name for classifier data
     * @return the model loaded
     */
    protected M load (String fileName)
    {
        // First, try user data, if any, in local EVAL folder
        logger.debug("AbstractClassifier. Trying user data");

        {
            final Path path = WellKnowns.TRAIN_FOLDER.resolve(fileName);

            if (Files.exists(path)) {
                try {
                    Path root = ZipFileSystem.open(path);
                    logger.debug("loadModel...");

                    M model = loadModel(root);
                    logger.debug("loadNorms...");
                    norms = loadNorms(root);
                    logger.debug("loaded.");
                    root.getFileSystem().close();

                    if (!isCompatible(model, norms)) {
                        final String msg = "Obsolete classifier user data in " + path
                                           + ", trying default data";
                        logger.warn(msg);
                    } else {
                        // Tell user we are not using the default
                        logger.info("Classifier data loaded from local {}", path);

                        return model; // Normal exit
                    }
                } catch (Exception ex) {
                    logger.warn("Load error {}", ex.toString(), ex);
                    norms = null;
                }
            }
        }

        // Second, use default data (in program RES folder)
        logger.debug("AbstractClassifier. Trying default data");

        final URI uri = UriUtil.toURI(WellKnowns.RES_URI, fileName);

        try {
            // Must be a path to a true zip *file*
            final Path zipPath;
            logger.debug("uri={}", uri);

            if (uri.toString().startsWith("jar:")) {
                // We have a .zip within a .jar
                // Quick fix: copy the .zip into a separate temp file
                // TODO: investigate a better solution!
                File tmpFile = File.createTempFile("AbstractClassifier-", ".tmp");
                logger.debug("tmpFile={}", tmpFile);
                tmpFile.deleteOnExit();

                try (InputStream is = uri.toURL().openStream()) {
                    FileUtils.copyInputStreamToFile(is, tmpFile);
                }
                zipPath = tmpFile.toPath();
            } else {
                zipPath = Paths.get(uri);
            }

            final Path root = ZipFileSystem.open(zipPath);
            M model = loadModel(root);
            norms = loadNorms(root);
            root.getFileSystem().close();

            if (!isCompatible(model, norms)) {
                final String msg = "Obsolete classifier default data in " + uri
                                   + ", please retrain from scratch";
                logger.warn(msg);
            } else {
                logger.info("Classifier data loaded from default uri {}", uri);

                return model; // Normal exit
            }
        } catch (Exception ex) {
            logger.warn("Load error on {} {}", uri, ex.toString(), ex);
        }

        norms = null; // No norms

        return null; // No model
    }

    //-----------//
    // loadModel //
    //-----------//
    /**
     * Load classifier model out of the provided input stream.
     * Method to be provided by subclass.
     *
     * @param root non-null root path of file system
     * @return the loaded model
     * @throws Exception if something goes wrong
     */
    protected abstract M loadModel (Path root)
            throws Exception;

    //------------//
    // storeModel //
    //------------//
    /**
     * Store the model to disk.
     *
     * @param modelPath path to model file
     * @throws Exception if something goes wrong
     */
    protected abstract void storeModel (Path modelPath)
            throws Exception;

    //-----------//
    // loadNorms //
    //-----------//
    /**
     * Try to load Norms data from the provided input file.
     *
     * @param root the root path to file system
     * @return the loaded Norms instance, or exception is thrown
     * @throws IOException   if something goes wrong during IO operations
     * @throws JAXBException if something goes wrong with XML deserialization
     */
    protected Norms loadNorms (Path root)
            throws Exception
    {
        INDArray means;
        INDArray stds = null;

        final Path meansEntry = root.resolve(MEANS_ENTRY_NAME);

        InputStream isMeans = Files.newInputStream(meansEntry); // READ by default
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(isMeans))) {
            means = Nd4j.read(dis);
            logger.info("means:{}", means);
        }

        final Path stdsEntry = root.resolve(STDS_ENTRY_NAME);

        if (stdsEntry != null) {
            InputStream is = Files.newInputStream(stdsEntry); // READ by default
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(is))) {
                stds = Nd4j.read(dis);
                logger.info("stds:{}", stds);
            }
        }

        if ((means != null) && (stds != null)) {
            return new Norms(means, stds);
        }

        throw new IllegalStateException("Norms were not found");
    }

    //-------//
    // store //
    //-------//
    /**
     * Store the engine internals, always as user files.
     *
     * @param fileName file name for classifier data (model &amp; norms)
     */
    protected void store (String fileName)
    {
        final Path path = WellKnowns.TRAIN_FOLDER.resolve(fileName);

        try {
            if (!Files.exists(WellKnowns.TRAIN_FOLDER)) {
                Files.createDirectories(WellKnowns.TRAIN_FOLDER);
                logger.info("Created directory {}", WellKnowns.TRAIN_FOLDER);
            }

            Path root = ZipFileSystem.create(path); // Delete if already exists

            storeModel(root);
            storeNorms(root);

            root.getFileSystem().close();

            logger.info("{} data stored to {}", getName(), path);
        } catch (Exception ex) {
            logger.warn("Error storing {} {}", getName(), ex.toString(), ex);
        }
    }

    //------------//
    // storeNorms //
    //------------//
    /**
     * Store the norms based on training samples.
     *
     * @param root path to root of file system
     * @throws IOException if something goes wrong during IO operations
     */
    protected void storeNorms (Path root)
            throws Exception
    {
        Path means = root.resolve(MEANS_ENTRY_NAME);
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(means, CREATE)))) {
            Nd4j.write(norms.means, dos);
            dos.flush();
        }

        Path stds = root.resolve(STDS_ENTRY_NAME);
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(stds, CREATE)))) {
            Nd4j.write(norms.stds, dos);
            dos.flush();
        }
    }

    //----------//
    // evaluate //
    //----------//
    private Evaluation[] evaluate (Glyph glyph,
                                   SystemInfo system,
                                   int count,
                                   double minGrade,
                                   EnumSet<Classifier.Condition> conditions,
                                   int interline)
    {
        List<Evaluation> bests = new ArrayList<>();
        Evaluation[] evals = getSortedEvaluations(glyph, interline);

        EvalsLoop:
        for (Evaluation eval : evals) {
            // Bounding test?
            if ((bests.size() >= count) || (eval.grade < minGrade)) {
                break;
            }

            // Successful checks?
            if ((conditions != null) && conditions.contains(Condition.CHECKED)) {
                // This may change the eval shape in only one case:
                // HW_REST_set may be changed for HALF_REST or WHOLE_REST based on pitch
                glyphChecker.annotate(system, eval, glyph);

                if (eval.failure != null) {
                    continue;
                }
            }

            // Everything is OK, add the shape if not already in the list
            // (this can happen when checks have modified the eval original shape)
            for (Evaluation e : bests) {
                if (e.shape == eval.shape) {
                    continue EvalsLoop;
                }
            }

            bests.add(eval);
        }

        return bests.toArray(new Evaluation[bests.size()]);
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-------//
    // Norms //
    //-------//
    /**
     * Class that encapsulates the means and standard deviations of glyph features.
     */
    protected static class Norms
    {

        /** Features means. */
        final INDArray means;

        /** Features standard deviations. */
        final INDArray stds;

        /**
         * Creates a new {@code Norms} object.
         *
         * @param means
         * @param stds
         */
        Norms (INDArray means,
               INDArray stds)
        {
            this.means = means;
            this.stds = stds;
        }
    }

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {

        private final Constant.Boolean printWatch = new Constant.Boolean(
                false,
                "Should we print out the stop watch?");

        private final Scale.AreaFraction minWeight = new Scale.AreaFraction(
                0.04,
                "Minimum normalized weight to be considered not a noise");
    }
}
