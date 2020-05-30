//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                             B o o k                                            //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2019. All rights reserved.
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
package org.audiveris.omr.sheet;

import org.audiveris.omr.OMR;
import org.audiveris.omr.ProgramId;
import org.audiveris.omr.WellKnowns;
import org.audiveris.omr.classifier.Annotations;
import org.audiveris.omr.classifier.SampleRepository;
import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.image.FilterDescriptor;
import org.audiveris.omr.image.FilterParam;
import org.audiveris.omr.image.ImageLoading;
import org.audiveris.omr.log.LogUtil;
import org.audiveris.omr.run.RunTable;
import org.audiveris.omr.score.OpusExporter;
import org.audiveris.omr.score.Page;
import org.audiveris.omr.score.PageRef;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.score.ScoreExporter;
import org.audiveris.omr.score.ScoreReduction;
import org.audiveris.omr.score.ui.BookPdfOutput;
import static org.audiveris.omr.sheet.Sheet.INTERNALS_RADIX;
import org.audiveris.omr.sheet.rhythm.Voices;
import org.audiveris.omr.sheet.ui.BookBrowser;
import org.audiveris.omr.sheet.ui.SheetResultPainter;
import org.audiveris.omr.sheet.ui.StubsController;
import org.audiveris.omr.step.ProcessingCancellationException;
import org.audiveris.omr.step.Step;
import org.audiveris.omr.step.StepException;
import org.audiveris.omr.step.ui.StepMonitoring;
import org.audiveris.omr.text.Language;
import org.audiveris.omr.util.FileUtil;
import org.audiveris.omr.util.Jaxb;
import org.audiveris.omr.util.Memory;
import org.audiveris.omr.util.OmrExecutors;
import org.audiveris.omr.util.StopWatch;
import org.audiveris.omr.util.ZipFileSystem;
import org.audiveris.omr.util.param.Param;
import org.audiveris.omr.util.param.StringParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipOutputStream;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * Class {@code Book} is the root class for handling a physical set of image input
 * files, resulting in one or several logical MusicXML scores.
 * <p>
 * A book instance generally corresponds to an input file containing one or several images, each
 * image resulting in a separate {@link Sheet} instance.
 * <p>
 * A sheet generally contains one or several systems.
 * An indented system (sometimes prefixed by part names) usually indicates a new movement.
 * Such indented system may appear in the middle of a sheet, thus (logical) movement frontiers do
 * not always match (physical) sheet frontiers.
 * <p>
 * A (super-) book may also contain (sub-) books to recursively gather a sequence of input files.
 * <p>
 * Methods are organized as follows:
 * <dl>
 * <dt>Administration</dt>
 * <dd>
 * <ul>
 * <li>{@link #getInputPath}</li>
 * <li>{@link #getAlias}</li>
 * <li>{@link #setAlias}</li>
 * <li>{@link #getRadix}</li>
 * <li>{@link #includeBook}</li>
 * <li>{@link #getOffset}</li>
 * <li>{@link #setOffset}</li>
 * <li>{@link #isDirty}</li>
 * <li>{@link #setDirty}</li>
 * <li>{@link #isModified}</li>
 * <li>{@link #setModified}</li>
 * <li>{@link #close}</li>
 * <li>{@link #closeFileSystem}</li>
 * <li>{@link #isClosing}</li>
 * <li>{@link #setClosing}</li>
 * <li>{@link #getLock}</li>
 * <li>{@link #openBookFile}</li>
 * <li>{@link #openSheetFolder}</li>
 * </ul>
 * </dd>
 *
 * <dt>SheetStubs</dt>
 * <dd>
 * <ul>
 * <li>{@link #createStubs}</li>
 * <li>{@link #createStubsTabs}</li>
 * <li>{@link #loadSheetImage}</li>
 * <li>{@link #isMultiSheet}</li>
 * <li>{@link #getStub}</li>
 * <li>{@link #getStubs}</li>
 * <li>{@link #getFirstValidStub}</li>
 * <li>{@link #getValidStubs}</li>
 * <li>{@link #removeStub}</li>
 * <li>{@link #hideInvalidStubs}</li>
 * <li>{@link #ids}</li>
 * <li>{@link #swapAllSheets}</li>
 * </ul>
 * </dd>
 *
 * <dt>Parameters</dt>
 * <dd>
 * <ul>
 * <li>{@link #getBinarizationFilter}</li>
 * <li>{@link #getOcrLanguages}</li>
 * <li>{@link #getProcessingSwitches}</li>
 * </ul>
 * </dd>
 *
 * <dt>Transcription</dt>
 * <dd>
 * <ul>
 * <li>{@link #reset}</li>
 * <li>{@link #resetToBinary}</li>
 * <li>{@link #transcribe}</li>
 * <li>{@link #reachBookStep}</li>
 * <li>{@link #updateScores}</li>
 * <li>{@link #reduceScores}</li>
 * <li>{@link #getScore}</li>
 * <li>{@link #getScores}</li>
 * </ul>
 * </dd>
 *
 * <dt>Samples</dt>
 * <dd>
 * <ul>
 * <li>{@link #getSampleRepository}</li>
 * <li>{@link #getSpecificSampleRepository}</li>
 * <li>{@link #hasAllocatedRepository}</li>
 * <li>{@link #hasSpecificRepository}</li>
 * <li>{@link #sample}</li>
 * <li>{@link #annotate}</li>
 * </ul>
 * </dd>
 *
 * <dt>Artifacts</dt>
 * <dd>
 * <ul>
 * <li>{@link #getBrowserFrame}</li>
 * <li>{@link #getExportPathSansExt}</li>
 * <li>{@link #setExportPathSansExt}</li>
 * <li>{@link #export}</li>
 * <li>{@link #getPrintPath}</li>
 * <li>{@link #setPrintPath}</li>
 * <li>{@link #print}</li>
 * <li>{@link #getBookPath}</li>
 * <li>{@link #store()}</li>
 * <li>{@link #store(java.nio.file.Path, boolean)}</li>
 * <li>{@link #storeBookInfo}</li>
 * <li>{@link #loadBook}</li>
 * </ul>
 * </dd>
 * </dl>
 *
 * <img src="doc-files/Book-Detail.png" alt="Book detals UML">
 *
 * @author Hervé Bitteur
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "book")
public class Book
{

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(Book.class);

    /** File name for book internals in book file system: {@value}. */
    public static final String BOOK_INTERNALS = "book.xml";

    /** Un/marshalling context for use with JAXB. */
    private static volatile JAXBContext jaxbContext;

    // Persistent data
    //----------------
    //
    /** Related Audiveris version that last operated on this book. */
    @XmlAttribute(name = "software-version")
    private String version;

    /** Related Audiveris build that last operated on this book. */
    @XmlAttribute(name = "software-build")
    private String build;

    /** Sub books, if any. */
    @XmlElement(name = "sub-books")
    private final List<Book> subBooks;

    /** Book alias, if any. */
    @XmlAttribute(name = "alias")
    private String alias;

    /** Input path of the related image(s) file, if any. */
    @XmlAttribute(name = "path")
    @XmlJavaTypeAdapter(Jaxb.PathAdapter.class)
    private final Path path;

    /** Sheet offset of image file with respect to full work, if any. */
    @XmlAttribute(name = "offset")
    private Integer offset;

    /** Indicate if the book scores must be updated. */
    @XmlAttribute(name = "dirty")
    @XmlJavaTypeAdapter(type = boolean.class, value = Jaxb.BooleanPositiveAdapter.class)
    private boolean dirty = false;

    /** Handling of binarization filter parameter. */
    @XmlElement(name = "binarization")
    @XmlJavaTypeAdapter(FilterParam.Adapter.class)
    private FilterParam binarizationFilter;

    /** Handling of dominant language(s) for this book. */
    @XmlElement(name = "ocr-languages")
    @XmlJavaTypeAdapter(StringParam.Adapter.class)
    private StringParam ocrLanguages;

    /** Handling of processing switches for this book. */
    @XmlElement(name = "processing")
    @XmlJavaTypeAdapter(ProcessingSwitches.Adapter.class)
    private ProcessingSwitches switches;

    /** Sequence of all sheets stubs got from image file. */
    @XmlElement(name = "sheet")
    private final List<SheetStub> stubs = new ArrayList<>();

    /** Logical scores for this book. */
    @XmlElement(name = "score")
    private final List<Score> scores = new ArrayList<>();

    // Transient data
    //---------------
    //
    /** Project file lock. */
    private final Lock lock = new ReentrantLock();

    /** The related file radix (file name without extension). */
    private String radix;

    /** File path where the book is kept. */
    private Path bookPath;

    /** File path where the book is printed. */
    private Path printPath;

    /** File path (without extension) where the MusicXML output is stored. */
    private Path exportPathSansExt;

    /** Browser on this book. */
    private BookBrowser bookBrowser;

    /** Flag to indicate this book is being closed. */
    private volatile boolean closing;

    /** Set if the book itself has been modified. */
    private boolean modified = false;

    /** Book-level sample repository. */
    private SampleRepository repository;

    /**
     * Create a Book with a path to an input images file.
     *
     * @param path the input image path (which may contain several images)
     */
    public Book (Path path)
    {
        Objects.requireNonNull(path, "Trying to create a Book with null path");

        this.path = path;
        subBooks = null;

        initTransients(FileUtil.getNameSansExtension(path), null);
    }

    /**
     * Create a meta Book, to be later populated with sub-books.
     * <p>
     * NOTA: This meta-book feature is not yet in use.
     *
     * @param nameSansExt a name (sans extension) for this book
     */
    public Book (String nameSansExt)
    {
        Objects.requireNonNull(nameSansExt, "Trying to create a meta Book with null name");

        path = null;
        subBooks = new ArrayList<>();

        initTransients(nameSansExt, null);
    }

    /**
     * No-arg constructor needed by JAXB.
     */
    public Book ()
    {
        path = null;
        subBooks = null;
    }

    //----------//
    // annotate //
    //----------//
    /**
     * Write the book symbol annotations.
     * <p>
     * Generate a whole zip file, in which each valid sheet is represented by a pair
     * composed of sheet image (.png) and sheet annotations (.xml).
     */
    public void annotate ()
    {
        Path root = null;

        try {
            final Path bookFolder = BookManager.getDefaultBookFolder(this);
            final Path path = bookFolder.resolve(
                    getRadix() + Annotations.BOOK_ANNOTATIONS_EXTENSION);
            root = ZipFileSystem.create(path);

            for (SheetStub stub : getValidStubs()) {
                try {
                    LogUtil.start(stub);

                    final Path sheetFolder = root.resolve(INTERNALS_RADIX + stub.getNumber());
                    final Sheet sheet = stub.getSheet();
                    sheet.annotate(sheetFolder);
                } catch (Exception ex) {
                    logger.warn("Error annotating {} {}", stub, ex.toString(), ex);
                } finally {
                    LogUtil.stopStub();
                }
            }

            logger.info("Book annotated as {}", path);
        } catch (IOException ex) {
            logger.warn("Error annotating book {} {}", this, ex.toString(), ex);
        } finally {
            if (root != null) {
                try {
                    root.getFileSystem().close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    //-------//
    // close //
    //-------//
    /**
     * Delete this book instance, as well as its related resources.
     */
    public void close ()
    {
        setClosing(true);

        // Close contained stubs/sheets
        if (OMR.gui != null) {
            SwingUtilities.invokeLater(new Runnable()
            {
                @Override
                public void run ()
                {
                    try {
                        LogUtil.start(Book.this);

                        for (SheetStub stub : new ArrayList<>(stubs)) {
                            LogUtil.start(stub);

                            // Close stub UI, if any
                            if (stub.getAssembly() != null) {
                                StubsController.getInstance().deleteAssembly(stub);
                                stub.getAssembly().close();
                            }
                        }
                    } finally {
                        LogUtil.stopBook();
                    }
                }
            });
        }

        // Close browser if any
        if (bookBrowser != null) {
            bookBrowser.close();
        }

        // Remove from OMR instances
        OMR.engine.removeBook(this);

        // Time for some cleanup...
        Memory.gc();

        logger.debug("Book closed.");
    }

    //-------------//
    // createStubs //
    //-------------//
    /**
     * Create as many sheet stubs as there are images in the input image file.
     * A created stub is nearly empty, the related image will have to be loaded later.
     *
     * @param sheetNumbers set of sheet numbers (1-based) explicitly included, null for all
     */
    public void createStubs (SortedSet<Integer> sheetNumbers)
    {
        ImageLoading.Loader loader = ImageLoading.getLoader(path);

        if (loader != null) {
            final int imageCount = loader.getImageCount();
            loader.dispose();
            logger.info("{} sheet{} in {}", imageCount, ((imageCount > 1) ? "s" : ""), path);

            if (sheetNumbers == null) {
                sheetNumbers = new TreeSet<>();
            }

            if (sheetNumbers.isEmpty()) {
                for (int i = 1; i <= imageCount; i++) {
                    sheetNumbers.add(i);
                }
            }

            for (int num : sheetNumbers) {
                stubs.add(new SheetStub(this, num));
            }
        }
    }

    //-----------------//
    // createStubsTabs //
    //-----------------//
    /**
     * Insert stubs assemblies in UI tabbed pane.
     * GUI will focus on first valid stub, unless a stub number is provided.
     *
     * @param focus the stub number to focus upon, or null
     */
    public void createStubsTabs (final Integer focus)
    {
        Runnable doRun = new Runnable()
        {
            @Override
            public void run ()
            {
                try {
                    LogUtil.start(Book.this);

                    final StubsController controller = StubsController.getInstance();

                    // Determine which stub should get the focus
                    SheetStub focusStub = null;

                    if (focus != null) {
                        if ((focus > 0) && (focus <= stubs.size())) {
                            focusStub = stubs.get(focus - 1);
                        } else {
                            logger.warn("Illegal focus sheet id: {}", focus);
                        }
                    }

                    if (focusStub == null) {
                        focusStub = getFirstValidStub(); // Focus on first valid stub, if any

                        if (focusStub == null) {
                            logger.info("No valid sheet in {}", this);
                        }
                    }

                    // Allocate one tab per stub, beginning by focusStub if any
                    Integer focusIndex = null;

                    if (focusStub != null) {
                        controller.addAssembly(focusStub.getAssembly(), null);
                        focusIndex = controller.getIndex(focusStub);

                        if (focusIndex == -1) {
                            focusIndex = null; // Safer
                        }
                    }

                    for (SheetStub stub : stubs) {
                        if (focusIndex == null) {
                            controller.addAssembly(stub.getAssembly(), null);
                        } else if (stub != focusStub) {
                            if (stub.getNumber() < focusStub.getNumber()) {
                                controller.addAssembly(
                                        stub.getAssembly(),
                                        controller.getLastIndex());
                            } else {
                                controller.addAssembly(stub.getAssembly(), null);
                            }
                        }
                    }

                    controller.adjustStubTabs(Book.this);
                } finally {
                    LogUtil.stopBook();
                }
            }
        };

        try {
            SwingUtilities.invokeAndWait(doRun);
        } catch (InterruptedException |
                 InvocationTargetException ex) {
            logger.warn("Error in createStubsTabs, {}", ex, ex);
        }
    }

    //--------//
    // export //
    //--------//
    /**
     * Export this book scores using MusicXML format.
     * <p>
     * Assuming 'BOOK' is the radix of book name, several outputs can be considered:
     * <ul>
     * <li>If we don't use opus and the book contains a single score, it is exported as "BOOK.ext"
     * where "ext" is either "mxl" or "xml" depending upon whether compression is used.</li>
     * <li>If we don't use opus and the book contains several scores, it is exported as several
     * "BOOK.mvt#.ext" files, where "#" stands for the movement number and "ext" is either "mxl" or
     * "xml" depending upon whether compression is used.</li>
     * <li>If we use opus, everything goes into "BOOK.opus.mxl" as a single container file.</li>
     * </ul>
     */
    public void export ()
    {
        // Make sure material is ready?
        transcribe();

        // path/to/scores/Book
        final Path bookPathSansExt = BookManager.getActualPath(
                getExportPathSansExt(),
                BookManager.getDefaultExportPathSansExt(this));
        final boolean compressed = BookManager.useCompression();
        final String ext = compressed ? OMR.COMPRESSED_SCORE_EXTENSION : OMR.SCORE_EXTENSION;
        final boolean sig = BookManager.useSignature();

        // Export each movement score
        String bookName = bookPathSansExt.getFileName().toString();
        final boolean multiMovements = scores.size() > 1;

        if (BookManager.useOpus()) {
            // Export the book as one opus file
            final Path opusPath = bookPathSansExt.resolveSibling(bookName + OMR.OPUS_EXTENSION);

            try {
                new OpusExporter(this).export(opusPath, bookName, sig);
            } catch (Exception ex) {
                logger.warn("Could not export opus " + opusPath, ex);
            }
        } else {
            // Export the book as one or several movement files
            for (Score score : scores) {
                final String scoreName = (!multiMovements) ? bookName
                        : (bookName + OMR.MOVEMENT_EXTENSION + score.getId());
                final Path scorePath = bookPathSansExt.resolveSibling(scoreName + ext);

                try {
                    new ScoreExporter(score).export(scorePath, scoreName, sig, compressed);
                } catch (Exception ex) {
                    logger.warn("Could not export score " + scoreName, ex);
                }
            }
        }
    }

    //----------//
    // getAlias //
    //----------//
    /**
     * Report the book name alias if any.
     *
     * @return book alias or null
     */
    public String getAlias ()
    {
        return alias;
    }

    //----------//
    // setAlias //
    //----------//
    /**
     * Set the book alias
     *
     * @param alias the book alias
     */
    public void setAlias (String alias)
    {
        this.alias = alias;
        radix = alias;
    }

    //-----------------------//
    // getBinarizationFilter //
    //-----------------------//
    /**
     * Report the binarization filter defined at book level.
     *
     * @return the filter parameter
     */
    public FilterParam getBinarizationFilter ()
    {
        if (binarizationFilter == null) {
            binarizationFilter = new FilterParam();
            binarizationFilter.setParent(FilterDescriptor.defaultFilter);
        }

        return binarizationFilter;
    }

    //-------------//
    // getBookPath //
    //-------------//
    /**
     * Report where the book is kept.
     *
     * @return the path to book .omr file
     */
    public Path getBookPath ()
    {
        return bookPath;
    }

    //-----------------//
    // getBrowserFrame //
    //-----------------//
    /**
     * Create a dedicated frame, where book hierarchy can be browsed interactively.
     *
     * @return the created frame
     */
    public JFrame getBrowserFrame ()
    {
        if (bookBrowser == null) {
            // Build the BookBrowser on the score
            bookBrowser = new BookBrowser(this);
        }

        return bookBrowser.getFrame();
    }

    //----------------------//
    // getExportPathSansExt //
    //----------------------//
    /**
     * Report the path (without extension) where book is to be exported.
     *
     * @return the book export path without extension, or null
     */
    public Path getExportPathSansExt ()
    {
        return exportPathSansExt;
    }

    //----------------------//
    // setExportPathSansExt //
    //----------------------//
    /**
     * Remember the path (without extension) where the book is to be exported.
     *
     * @param exportPathSansExt the book export path (without extension)
     */
    public void setExportPathSansExt (Path exportPathSansExt)
    {
        this.exportPathSansExt = exportPathSansExt;
    }

    //-------------------//
    // getFirstValidStub //
    //-------------------//
    /**
     * Report the first non-discarded stub in this book
     *
     * @return the first non-discarded stub, or null
     */
    public SheetStub getFirstValidStub ()
    {
        for (SheetStub stub : stubs) {
            if (stub.isValid()) {
                return stub;
            }
        }

        return null; // No valid stub found!
    }

    //--------------//
    // getInputPath //
    //--------------//
    /**
     * Report the path to the book image(s) input.
     *
     * @return the image input path
     */
    public Path getInputPath ()
    {
        return path;
    }

    //---------//
    // getLock //
    //---------//
    /**
     * Report the lock that protects book project file.
     *
     * @return book project lock
     */
    public Lock getLock ()
    {
        return lock;
    }

    //-----------------//
    // getOcrLanguages //
    //-----------------//
    /**
     * Report the OCR language(s) specification defined at book level, if any.
     *
     * @return the OCR language(s) spec
     */
    public Param<String> getOcrLanguages ()
    {
        if (ocrLanguages == null) {
            ocrLanguages = new StringParam();
            ocrLanguages.setParent(Language.ocrDefaultLanguages);
        }

        return ocrLanguages;
    }

    //-----------//
    // getOffset //
    //-----------//
    /**
     * Report the offset of this book, with respect to a containing super-book.
     *
     * @return the offset (in terms of number of sheets)
     */
    public Integer getOffset ()
    {
        return offset;
    }

    //-----------//
    // setOffset //
    //-----------//
    /**
     * Assign this book offset (WRT containing super-book)
     *
     * @param offset the offset to set
     */
    public void setOffset (Integer offset)
    {
        this.offset = offset;
    }

    //--------------//
    // getPrintPath //
    //--------------//
    /**
     * Report the path, if any, where book is to be printed.
     *
     * @return the print path, or null
     */
    public Path getPrintPath ()
    {
        return printPath;
    }

    //--------------//
    // setPrintPath //
    //--------------//
    /**
     * Remember to which path book print data is to be written.
     *
     * @param printPath the print path
     */
    public void setPrintPath (Path printPath)
    {
        this.printPath = printPath;
    }

    //-----------------------//
    // getProcessingSwitches //
    //-----------------------//
    /**
     * Report the processing switches defined at book level, if any.
     *
     * @return the processing switches
     */
    public ProcessingSwitches getProcessingSwitches ()
    {
        if (switches == null) {
            switches = new ProcessingSwitches();
            switches.setParent(ProcessingSwitches.getDefaultSwitches());
        }

        return switches;
    }

    //----------//
    // getRadix //
    //----------//
    /**
     * Report the radix of the file that corresponds to the book.
     * It is based on the simple file name of the book, with no path and no extension.
     *
     * @return the book input file radix
     */
    public String getRadix ()
    {
        return radix;
    }

    //---------------------//
    // getSampleRepository //
    //---------------------//
    /**
     * Report the sample repository (specific or global) to populate for this book
     *
     * @return a specific book repository if possible, otherwise the global one
     */
    public SampleRepository getSampleRepository ()
    {
        SampleRepository repo = getSpecificSampleRepository();

        if (repo != null) {
            return repo;
        }

        // No specific repository is possible, so use global
        return SampleRepository.getGlobalInstance();
    }

    //----------//
    // getScore //
    //----------//
    /**
     * Report the score which contains the provided page.
     *
     * @param page provided page
     * @return containing score (can it be null?)
     */
    public Score getScore (Page page)
    {
        for (Score score : scores) {
            int pageIndex = score.getPageIndex(page);

            if (pageIndex != -1) {
                return score;
            }
        }

        return null;
    }

    //-----------//
    // getScores //
    //-----------//
    /**
     * Report the scores (movements) detected in this book.
     *
     * @return the immutable list of scores
     */
    public List<Score> getScores ()
    {
        return Collections.unmodifiableList(scores);
    }

    //-----------------------------//
    // getSpecificSampleRepository //
    //-----------------------------//
    /**
     * Report (after allocation if needed) the book <b>specific</b> sample repository
     *
     * @return the repository instance with material for this book only, or null
     */
    public SampleRepository getSpecificSampleRepository ()
    {
        if (repository == null) {
            repository = SampleRepository.getInstance(this, true);
        }

        return repository;
    }

    //---------//
    // getStub //
    //---------//
    /**
     * Report the sheet stub with provided id (counted from 1).
     *
     * @param sheetId the desired value for sheet id
     * @return the proper sheet stub, or null if not found
     */
    public SheetStub getStub (int sheetId)
    {
        return stubs.get(sheetId - 1);
    }

    //----------//
    // getStubs //
    //----------//
    /**
     * Report all the sheets stubs contained in this book.
     *
     * @return the immutable list of sheets stubs, list may be empty but is never null
     */
    public List<SheetStub> getStubs ()
    {
        return Collections.unmodifiableList(stubs);
    }

    //---------------//
    // getValidStubs //
    //---------------//
    /**
     * Report the non-discarded sheets stubs in this book.
     *
     * @return the immutable list of valid sheets stubs
     */
    public List<SheetStub> getValidStubs ()
    {
        List<SheetStub> valids = new ArrayList<>();

        for (SheetStub stub : stubs) {
            if (stub.isValid()) {
                valids.add(stub);
            }
        }

        return valids;
    }

    //------------------------//
    // hasAllocatedRepository //
    //------------------------//
    /**
     * Tell whether the book has allocated a dedicated sample repository.
     *
     * @return true if allocated
     */
    public boolean hasAllocatedRepository ()
    {
        return repository != null;
    }

    //-----------------------//
    // hasSpecificRepository //
    //-----------------------//
    /**
     * Tell whether the book has an existing specific sample repository.
     *
     * @return true if specific repository exists
     */
    public boolean hasSpecificRepository ()
    {
        if (repository != null) {
            return true;
        }

        // Look for needed files
        return SampleRepository.repositoryExists(this);
    }

    //------------------//
    // hideInvalidStubs //
    //------------------//
    /**
     * Hide stub assemblies of invalid sheets.
     */
    public void hideInvalidStubs ()
    {
        SwingUtilities.invokeLater(new Runnable()
        {
            @Override
            public void run ()
            {
                final StubsController controller = StubsController.getInstance();

                for (SheetStub stub : stubs) {
                    if (!stub.isValid()) {
                        controller.removeAssembly(stub);
                    }
                }
            }
        });
    }

    //-------------//
    // includeBook //
    //-------------//
    /**
     * Include a (sub) book into this (super) book.
     *
     * @param book the sub book to include
     */
    public void includeBook (Book book)
    {
        subBooks.add(book);
    }

    //-----------//
    // isClosing //
    //-----------//
    /**
     * Report whether this book is being closed.
     *
     * @return the closing flag
     */
    public boolean isClosing ()
    {
        return closing;
    }

    //------------//
    // setClosing //
    //------------//
    /**
     * Flag this book as closing.
     *
     * @param closing the closing to set
     */
    public void setClosing (boolean closing)
    {
        this.closing = closing;
    }

    //---------//
    // isDirty //
    //---------//
    /**
     * Report whether the book scores need to be reduced.
     *
     * @return true if dirty
     */
    public boolean isDirty ()
    {
        return dirty;
    }

    //----------//
    // setDirty //
    //----------//
    /**
     * Set the dirty flag.
     *
     * @param dirty the new flag value
     */
    public void setDirty (boolean dirty)
    {
        this.dirty = dirty;
    }

    //------------//
    // isModified //
    //------------//
    /**
     * Report whether the book has been modified with respect to its persisted data.
     *
     * @return true if modified
     */
    public boolean isModified ()
    {
        if (modified) {
            return true; // The book itself is modified
        }

        if ((repository != null) && repository.isModified()) {
            return true; // The book sample repository is modified
        }

        for (SheetStub stub : stubs) {
            if (stub.isModified()) {
                return true; // This sheet is modified
            }
        }

        return false;
    }

    //-------------//
    // setModified //
    //-------------//
    /**
     * Set the modified flag.
     *
     * @param modified the new flag value
     */
    public void setModified (boolean modified)
    {
        this.modified = modified;

        if (OMR.gui != null) {
            SwingUtilities.invokeLater(new Runnable()
            {
                @Override
                public void run ()
                {
                    final StubsController controller = StubsController.getInstance();
                    final SheetStub stub = controller.getSelectedStub();

                    if ((stub != null) && (stub.getBook() == Book.this)) {
                        controller.refresh();
                    }
                }
            });
        }
    }

    //------------//
    // isUpgraded //
    //------------//
    /**
     * Report whether the book has been upgraded with respect to its persisted data.
     *
     * @return true if upgraded
     */
    public boolean isUpgraded ()
    {
        for (SheetStub stub : stubs) {
            if (stub.isUpgraded()) {
                return true; // This sheet is upgraded
            }
        }

        return false;
    }

    //--------------//
    // isMultiSheet //
    //--------------//
    /**
     * Report whether this book contains several sheets.
     *
     * @return true for several sheets
     */
    public boolean isMultiSheet ()
    {
        return stubs.size() > 1;
    }

    //----------------//
    // loadSheetImage //
    //----------------//
    /**
     * Actually load the image that corresponds to the specified sheet id.
     *
     * @param id specified sheet id
     * @return the loaded sheet image
     */
    public BufferedImage loadSheetImage (int id)
    {
        try {
            final ImageLoading.Loader loader = ImageLoading.getLoader(path);

            if (loader == null) {
                return null;
            }

            BufferedImage img = loader.getImage(id);
            logger.info("Loaded image {} {}x{} from {}", id, img.getWidth(), img.getHeight(), path);

            loader.dispose();

            return img;
        } catch (IOException ex) {
            logger.warn("Error in book.loadSheetImage", ex);

            return null;
        }
    }

    //--------------//
    // openBookFile //
    //--------------//
    /**
     * Open the book file (supposed to already exist at location provided by
     * '{@code bookPath}' member) for reading or writing.
     * <p>
     * When IO operations are finished, the book file must be closed via
     * {@link #closeFileSystem(java.nio.file.FileSystem)}
     *
     * @return the root path of the (zipped) book file system
     * @throws java.io.IOException if anything goes wrong
     */
    public Path openBookFile ()
            throws IOException
    {
        return ZipFileSystem.open(bookPath);
    }

    //-----------------//
    // openSheetFolder //
    //-----------------//
    /**
     * Open (in the book zipped file) the folder for provided sheet number
     *
     * @param number sheet number (1-based) within the book
     * @return the path to sheet folder
     * @throws IOException if anything goes wrong
     */
    public Path openSheetFolder (int number)
            throws IOException
    {
        Path root = openBookFile();

        return root.resolve(INTERNALS_RADIX + number);
    }

    //-------//
    // print //
    //-------//
    /**
     * Print this book in PDF format.
     */
    public void print ()
    {
        // Path to print file
        final Path pdfPath = BookManager.getActualPath(
                getPrintPath(),
                BookManager.getDefaultPrintPath(this));

        try {
            new BookPdfOutput(Book.this, pdfPath.toFile()).write(
                    null,
                    new SheetResultPainter.PdfResultPainter());
            setPrintPath(pdfPath);
        } catch (Exception ex) {
            logger.warn("Cannot print to {} {}", pdfPath, ex.toString(), ex);
        }
    }

    //---------------//
    // reachBookStep //
    //---------------//
    /**
     * Reach a specific step (and all needed intermediate steps) on all valid sheets
     * of this book.
     *
     * @param target   the targeted step
     * @param force    if true and step already reached, sheet is reset and processed until step
     * @param sheetIds IDs of selected valid sheets, or null for all valid sheets
     * @return true if OK on all sheet actions
     */
    public boolean reachBookStep (final Step target,
                                  final boolean force,
                                  final Set<Integer> sheetIds)
    {
        try {
            final List<SheetStub> concernedStubs = getConcernedStubs(sheetIds);
            logger.debug("reachStep {} force:{} sheetIds:{}", target, force, sheetIds);

            if (!force) {
                // Check against the least advanced step performed across all sheets concerned
                Step least = getLeastStep(concernedStubs);

                if ((least != null) && (least.compareTo(target) >= 0)) {
                    return true; // Nothing to do
                }
            }

            // Launch the steps on each sheet
            long startTime = System.currentTimeMillis();
            logger.info(
                    "Book reaching {}{} on sheets:{}",
                    target,
                    force ? " force" : "",
                    ids(concernedStubs));

            try {
                boolean someFailure = false;
                StepMonitoring.notifyStart();

                if (isMultiSheet() && constants.processAllStubsInParallel.isSet()
                            && (OmrExecutors.defaultParallelism.getValue() == true)) {
                    // Process all stubs in parallel
                    List<Callable<Boolean>> tasks = new ArrayList<>();

                    for (final SheetStub stub : concernedStubs) {
                        tasks.add(new Callable<Boolean>()
                        {
                            @Override
                            public Boolean call ()
                                    throws StepException
                            {
                                LogUtil.start(stub);

                                try {
                                    boolean ok = stub.reachStep(target, force);

                                    if (ok && (OMR.gui == null)) {
                                        stub.swapSheet(); // Save sheet & global book info to disk
                                    }

                                    return ok;
                                } finally {
                                    LogUtil.stopStub();
                                }
                            }
                        });
                    }

                    try {
                        List<Future<Boolean>> futures = OmrExecutors.getCachedLowExecutor()
                                .invokeAll(tasks);

                        for (Future<Boolean> future : futures) {
                            try {
                                if (!future.get()) {
                                    someFailure = true;
                                }
                            } catch (InterruptedException |
                                     ExecutionException ex) {
                                logger.warn("Future exception", ex);
                                someFailure = true;
                            }
                        }

                        return !someFailure;
                    } catch (InterruptedException ex) {
                        logger.warn("Error in parallel reachBookStep", ex);
                        someFailure = true;
                    }
                } else {
                    // Process one stub after the other
                    for (SheetStub stub : concernedStubs) {
                        LogUtil.start(stub);

                        try {
                            if (stub.reachStep(target, force)) {
                                if (OMR.gui == null) {
                                    stub.swapSheet(); // Save sheet & global book info to disk
                                }
                            } else {
                                someFailure = true;
                            }
                        } catch (Exception ex) {
                            // Exception (such as timeout) raised on stub
                            // Let processing continue for the other stubs
                            logger.warn("Error processing stub");
                            someFailure = true;
                        } finally {
                            LogUtil.stopStub();
                        }
                    }
                }

                return !someFailure;
            } finally {
                LogUtil.stopStub();
                StepMonitoring.notifyStop();

                long stopTime = System.currentTimeMillis();
                logger.debug("End of step set in {} ms.", (stopTime - startTime));
            }
        } catch (ProcessingCancellationException pce) {
            throw pce;
        } catch (Exception ex) {
            logger.warn("Error in performing " + target, ex);
        }

        return false;
    }

    //--------------//
    // reduceScores //
    //--------------//
    /**
     * Determine the logical parts of each score.
     *
     * @return the count of modifications done
     */
    public int reduceScores ()
    {
        int modifs = 0;

        if (scores != null) {
            for (Score score : scores) {
                // (re) build the score logical parts
                modifs += new ScoreReduction(score).reduce();

                // Slurs and voices connection across pages in score
                modifs += Voices.refineScore(score);
            }

            if (modifs > 0) {
                setModified(true);
                logger.info("Scores built: {}", scores.size());
            }

            setDirty(false);
        }

        return modifs;
    }

    //------------//
    // removeStub //
    //------------//
    /**
     * Remove the specified sheet stub from the containing book.
     * <p>
     * Typically, when the sheet carries no music information, it can be removed from the book
     * (without changing the IDs of the sibling sheets in the book)
     *
     * @param stub the sheet stub to remove
     * @return true if actually removed
     */
    public boolean removeStub (SheetStub stub)
    {
        return stubs.remove(stub);
    }

    //-------//
    // reset //
    //-------//
    /**
     * Reset all valid sheets of this book to their initial state.
     */
    public void reset ()
    {
        for (SheetStub stub : getValidStubs()) {
            stub.reset();
        }

        scores.clear();
    }

    //---------------//
    // resetToBinary //
    //---------------//
    /**
     * Reset all valid sheets of this book to their BINARY step.
     */
    public void resetToBinary ()
    {
        for (SheetStub stub : getValidStubs()) {
            stub.resetToBinary();
        }

        scores.clear();
    }

    //--------//
    // sample //
    //--------//
    /**
     * Write the book symbol samples into its sample repository.
     */
    public void sample ()
    {
        for (SheetStub stub : getValidStubs()) {
            Sheet sheet = stub.getSheet();
            sheet.sample();
        }
    }

    //-------//
    // store //
    //-------//
    /**
     * Store book to disk.
     *
     * @param bookPath   target path for storing the book
     * @param withBackup if true, rename beforehand any existing target as a backup
     */
    public void store (Path bookPath,
                       boolean withBackup)
    {
        Memory.gc(); // Launch garbage collection, to save on weak glyph references ...

        boolean diskWritten = false; // Has disk actually been written?

        // Backup existing book file?
        if (withBackup && Files.exists(bookPath)) {
            Path backup = FileUtil.backup(bookPath);

            if (backup != null) {
                logger.info("Previous book file renamed as {}", backup);
            }
        }

        Path root = null; // Root of the zip file system

        try {
            getLock().lock();
            checkRadixChange(bookPath);
            logger.debug("Storing book...");

            if ((this.bookPath == null) || this.bookPath.toAbsolutePath().equals(
                    bookPath.toAbsolutePath())) {
                if (this.bookPath == null) {
                    root = ZipFileSystem.create(bookPath);
                    diskWritten = true;
                } else {
                    root = ZipFileSystem.open(bookPath);
                }

                if (modified) {
                    storeBookInfo(root); // Book info (book.xml)
                    diskWritten = true;
                }

                // Contained sheets
                for (SheetStub stub : stubs) {
                    if (stub.isModified() || stub.isUpgraded()) {
                        final Path sheetFolder = root.resolve(INTERNALS_RADIX + stub.getNumber());
                        stub.getSheet().store(sheetFolder, null);
                        diskWritten = true;
                    }
                }

                // Separate repository
                if ((repository != null) && repository.isModified()) {
                    repository.storeRepository();
                }
            } else {
                // (Store as): Switch from old to new book file
                root = createBookFile(bookPath);
                diskWritten = true;

                storeBookInfo(root); // Book info (book.xml)

                // Contained sheets
                final Path oldRoot = openBookFile(this.bookPath);

                for (SheetStub stub : stubs) {
                    final Path oldSheetFolder = oldRoot.resolve(INTERNALS_RADIX + stub.getNumber());
                    final Path sheetFolder = root.resolve(INTERNALS_RADIX + stub.getNumber());

                    // By default, copy existing sheet files
                    if (Files.exists(oldSheetFolder)) {
                        FileUtil.copyTree(oldSheetFolder, sheetFolder);
                    }

                    // Update modified sheet files
                    if (stub.isModified() || stub.isUpgraded()) {
                        stub.getSheet().store(sheetFolder, oldSheetFolder);
                    }
                }

                oldRoot.getFileSystem().close(); // Close old book file
            }

            this.bookPath = bookPath;

            BookManager.getInstance().getBookHistory().add(bookPath); // Insert in history

            if (diskWritten) {
                logger.info("Book stored as {}", bookPath);
            }
        } catch (Exception ex) {
            logger.warn("Error storing " + this + " to " + bookPath + " ex:" + ex, ex);
        } finally {
            if (root != null) {
                try {
                    root.getFileSystem().close();
                } catch (IOException ignored) {
                }
            }

            getLock().unlock();
        }
    }

    //-------//
    // store //
    //-------//
    /**
     * Store book to disk, using its current book path.
     */
    public void store ()
    {
        if (bookPath == null) {
            logger.warn("Bookpath not defined");
        } else {
            store(bookPath, false);
        }
    }

    //---------------//
    // storeBookInfo //
    //---------------//
    /**
     * Store the book information (global info + stub steps) into book file system.
     *
     * @param root root path of book file system
     * @throws Exception if anything goes wrong
     */
    public void storeBookInfo (Path root)
            throws Exception
    {
        Path bookInternals = root.resolve(BOOK_INTERNALS);
        Files.deleteIfExists(bookInternals);
        Jaxb.marshal(this, bookInternals, getJaxbContext());
        setModified(false);
        logger.info("Stored {}", bookInternals);
    }

    //---------------//
    // swapAllSheets //
    //---------------//
    /**
     * Swap all sheets, except the current one if any.
     */
    public void swapAllSheets ()
    {
        if (isModified() || isUpgraded()) {
            logger.info("{} storing", this);
            store();
        }

        SheetStub currentStub = null;

        if (OMR.gui != null) {
            currentStub = StubsController.getCurrentStub();
        }

        for (SheetStub stub : stubs) {
            if (stub != currentStub) {
                stub.swapSheet();
            }
        }
    }

    //----------//
    // toString //
    //----------//
    @Override
    public String toString ()
    {
        StringBuilder sb = new StringBuilder("Book{");
        sb.append(radix);

        if ((offset != null) && (offset > 0)) {
            sb.append(" offset:").append(offset);
        }

        sb.append("}");

        return sb.toString();
    }

    //------------//
    // transcribe //
    //------------//
    /**
     * Convenient method to perform all needed transcription steps on all valid sheets
     * of this book and building the book score(s).
     *
     * @return true if OK
     */
    public boolean transcribe ()
    {
        boolean ok = reachBookStep(Step.last(), false, null);

        reduceScores();

        return ok;
    }

    //--------------//
    // updateScores //
    //--------------//
    /**
     * Update the gathering of sheet pages into scores.
     * <p>
     * The question is which scores should we update.
     * Clearing all and rebuilding all is OK for pageRefs of all scores without loading sheets.
     * But doing so, we lose logicalPart information of <b>all</b> scores, and to rebuild it we'll
     * need to reload all valid sheets.
     * <p>
     * A better approach is to check the stub before and the stub after the current one.
     * This may result in the addition or the removal of scores.
     *
     * @param currentStub the current stub
     */
    public synchronized void updateScores (SheetStub currentStub)
    {
        if (scores.isEmpty()) {
            // Easy: allocate scores based on all book stubs
            createScores();
        } else {
            try {
                // Determine just the impacted pageRefs
                final SortedSet<PageRef> impactedRefs = new TreeSet<>();
                final int stubNumber = currentStub.getNumber();

                if (!currentStub.getPageRefs().isEmpty()) {
                    // Look in stub before current stub?
                    final PageRef firstPageRef = currentStub.getFirstPageRef();

                    if (!firstPageRef.isMovementStart()) {
                        final SheetStub prevStub = (stubNumber > 1) ? stubs.get(stubNumber - 2)
                                : null;

                        if (prevStub != null) {
                            final PageRef prevPageRef = prevStub.getLastPageRef();

                            if (prevPageRef != null) {
                                impactedRefs.addAll(getScore(prevPageRef).getPageRefs()); // NPE
                            }
                        }
                    }

                    // Take pages of current stub
                    impactedRefs.addAll(currentStub.getPageRefs());

                    // Look in stub after current stub?
                    final SheetStub nextStub = (stubNumber < stubs.size()) ? stubs.get(stubNumber)
                            : null;

                    if (nextStub != null) {
                        final PageRef nextPageRef = nextStub.getFirstPageRef();

                        if ((nextPageRef != null) && !nextPageRef.isMovementStart()) {
                            impactedRefs.addAll(getScore(nextPageRef).getPageRefs()); // NPE
                        }
                    }
                }

                // Determine and remove the impacted scores
                final List<Score> impactedScores = scoresOf(impactedRefs);
                Integer scoreIndex = null;

                if (!impactedScores.isEmpty()) {
                    scoreIndex = scores.indexOf(impactedScores.get(0));
                } else {
                    for (Score score : scores) {
                        if (score.getFirstPageRef().getSheetNumber() > stubNumber) {
                            scoreIndex = scores.indexOf(score);

                            break;
                        }
                    }
                }

                if (scoreIndex == null) {
                    scoreIndex = scores.size();
                }

                logger.debug("Impacted pages:{} scores:{}", impactedRefs, impactedScores);
                scores.removeAll(impactedScores);

                // Insert new score(s) to replace the impacted one(s)?
                if (!currentStub.isValid()) {
                    impactedRefs.removeAll(currentStub.getPageRefs());
                }

                insertScores(currentStub, impactedRefs, scoreIndex);
            } catch (Exception ex) {
                // This seems to result from inconsistency between scores info and stubs info.
                // Initial cause can be a sheet not marshalled (because of use by another process)
                // followed by a reload of now non-consistent book.xml

                // Workaround: Clear all scores and rebuild them from stubs info
                // (Doing so, we may lose logical-part informations)
                logger.warn("Error updating scores " + ex, ex);
                logger.warn("Rebuilding them from stubs info.");
                scores.clear();
                createScores();
            }
        }
    }

    //-----------------------//
    // areVersionsCompatible //
    //-----------------------//
    /**
     * Check whether the program version can operate on the file version.
     * <p>
     * All version strings are expected to be formatted like "5.0" or "5.0.1"
     * and we don't take the 3rd number, if any, into account for compatibility checking.
     *
     * @param programVersion version of software
     * @param fileVersion    version of book file
     * @return true if OK
     */
    private boolean areVersionsCompatible (String programVersion,
                                           String fileVersion)
    {
        try {
            logger.debug("Book file version: {}", fileVersion);

            final String[] programTokens = programVersion.split("\\.");

            if (programTokens.length < 2) {
                throw new IllegalArgumentException("Illegal Audiveris version " + programVersion);
            }

            final String[] fileTokens = fileVersion.split("\\.");

            if (fileTokens.length < 2) {
                throw new IllegalArgumentException("Illegal Book file version " + fileVersion);
            }

            for (int i = 0; i < 2; i++) {
                if (Integer.decode(fileTokens[i]) < Integer.decode(programTokens[i])) {
                    return false;
                }
            }

            return true;
        } catch (IllegalArgumentException ex) {
            logger.error("Error while checking versions " + ex, ex);

            return false; // Safer
        }
    }

    //---------------//
    // beforeMarshal //
    //---------------//
    @SuppressWarnings("unused")
    private void beforeMarshal (Marshaller m)
    {
        if ((binarizationFilter != null) && !binarizationFilter.isSpecific()) {
            binarizationFilter = null;
        }

        if ((ocrLanguages != null) && !ocrLanguages.isSpecific()) {
            ocrLanguages = null;
        }

        if ((switches != null) && switches.isEmpty()) {
            switches = null;
        }
    }

    //------------------//
    // checkRadixChange //
    //------------------//
    /**
     * If the (new) book name does not match current one, update the book radix
     * (and the title of first displayed sheet if any).
     *
     * @param bookPath new book target path
     */
    private void checkRadixChange (Path bookPath)
    {
        // Are we changing the target name WRT the default name?
        final String newRadix = FileUtil.avoidExtensions(bookPath.getFileName(), OMR.BOOK_EXTENSION)
                .toString();

        if (!newRadix.equals(radix)) {
            // Update book radix
            radix = newRadix;

            // We are really changing the radix, so nullify all other paths
            exportPathSansExt = printPath = null;

            if (OMR.gui != null) {
                // Update UI first sheet tab
                SwingUtilities.invokeLater(new Runnable()
                {
                    @Override
                    public void run ()
                    {
                        StubsController.getInstance().updateFirstStubTitle(Book.this);
                    }
                });
            }
        }
    }

    //------------//
    // checkScore // Dirty hack, to be removed ASAP
    //------------//
    private void checkScore ()
    {
        for (Score score : scores) {
            PageRef ref = score.getFirstPageRef();

            if (ref == null) {
                logger.warn("Discarding invalid score data.");
                scores.clear();

                break;
            }
        }
    }

    //--------------//
    // createScores //
    //--------------//
    /**
     * Create scores out of all book stubs.
     */
    private void createScores ()
    {
        Score score = null;

        // Group provided sheets pages into scores
        for (SheetStub stub : stubs) {
            // An invalid or not-yet-processed stub triggers a score break
            if (stub.getPageRefs().isEmpty()) {
                score = null;
            } else {
                for (PageRef pageRef : stub.getPageRefs()) {
                    if ((score == null) || pageRef.isMovementStart()) {
                        scores.add(score = new Score());
                        score.setBook(this);
                    }

                    score.addPageRef(stub.getNumber(), pageRef);
                }
            }
        }

        logger.debug("Created scores:{}", scores);
    }

    //-------------------//
    // getConcernedStubs //
    //-------------------//
    private List<SheetStub> getConcernedStubs (Set<Integer> sheetIds)
    {
        List<SheetStub> list = new ArrayList<>();

        for (SheetStub stub : getValidStubs()) {
            if ((sheetIds == null) || sheetIds.contains(stub.getNumber())) {
                list.add(stub);
            }
        }

        return list;
    }

    //--------------//
    // getLeastStep //
    //--------------//
    /**
     * Report the least advanced step reached among all provided stubs.
     *
     * @return the least step, null if any stub has not reached the first step (LOAD)
     */
    private Step getLeastStep (List<SheetStub> stubs)
    {
        Step least = Step.last();

        for (SheetStub stub : stubs) {
            Step latest = stub.getLatestStep();

            if (latest == null) {
                return null; // This sheet has not been processed at all
            }

            if (latest.compareTo(least) < 0) {
                least = latest;
            }
        }

        return least;
    }

    //----------//
    // getScore //
    //----------//
    /**
     * Report the score if any that contains the provided PageRef.
     *
     * @param pageRef the provided page ref (sheet#, page#)
     * @return the containing score or null if not found
     */
    private Score getScore (PageRef pageRef)
    {
        for (Score score : scores) {
            PageRef ref = score.getPageRef(pageRef.getSheetNumber());

            if ((ref != null) && (ref.getId() == pageRef.getId())) {
                return score;
            }
        }

        return null;
    }

    //----------------//
    // initTransients //
    //----------------//
    /**
     * Initialize transient data.
     *
     * @param nameSansExt book name without extension, if any
     * @param bookPath    full path to book .omr file, if any
     * @return true if OK
     */
    private boolean initTransients (String nameSansExt,
                                    Path bookPath)
    {
        if (binarizationFilter != null) {
            binarizationFilter.setParent(FilterDescriptor.defaultFilter);
        }

        if (ocrLanguages != null) {
            ocrLanguages.setParent(Language.ocrDefaultLanguages);
        }

        if (switches != null) {
            switches.setParent(ProcessingSwitches.getDefaultSwitches());
        }

        if (alias == null) {
            alias = checkAlias(getInputPath());

            if (alias != null) {
                nameSansExt = alias;
            }
        }

        if (nameSansExt != null) {
            radix = nameSansExt;
        }

        if (bookPath != null) {
            this.bookPath = bookPath;

            if (nameSansExt == null) {
                radix = FileUtil.getNameSansExtension(bookPath);
            }
        }

        if (build == null) {
            build = WellKnowns.TOOL_BUILD;
        }

        if (version == null) {
            version = WellKnowns.TOOL_REF;
        } else {
            if (constants.checkBookVersion.isSet()) {
                // Check compatibility between file version and program version
                if (!areVersionsCompatible(ProgramId.PROGRAM_VERSION, version)) {
                    if (constants.resetOldBooks.isSet()) {
                        final String msg = bookPath + " version " + version;
                        logger.warn(msg);

                        // Prompt user for resetting project sheets?
                        if ((OMR.gui == null) || OMR.gui.displayConfirmation(
                                msg + "\nConfirm reset to binary?",
                                "Non compatible book version")) {
                            resetToBinary();
                            logger.info("Book {} reset to binary.", radix);
                            version = WellKnowns.TOOL_REF;
                            build = WellKnowns.TOOL_BUILD;

                            return true;
                        }
                    } else {
                        logger.info("Incompatible book version, but not reset.");
                    }

                    return false;
                }
            }
        }

        return true;
    }

    //--------------//
    // insertScores //
    //--------------//
    /**
     * Insert scores out of provided sequence of PageRef's.
     *
     * @param currentStub stub being processed
     * @param pageRefs    sequence of pageRefs
     * @param insertIndex insertion index in scores list
     */
    private void insertScores (SheetStub currentStub,
                               SortedSet<PageRef> pageRefs,
                               int insertIndex)
    {
        Score score = null;
        Integer stubNumber = null;
        int index = insertIndex;

        for (PageRef ref : pageRefs) {
            if (stubNumber == null) {
                // Very first
                score = null;
            } else if (stubNumber < (ref.getSheetNumber() - 1)) {
                // One or several stubs missing
                score = null;
            }

            if (ref.isMovementStart()) {
                // Movement start
                score = null;
            }

            if (score == null) {
                scores.add(index++, score = new Score());
                score.setBook(this);
            }

            score.addPageRef(ref.getSheetNumber(), ref);
            stubNumber = ref.getSheetNumber();
        }

        logger.debug("Inserted scores:{}", scores.subList(insertIndex, index));
    }

    //----------//
    // scoresOf //
    //----------//
    /**
     * Retrieve the list of scores that embrace the provided sequence of pageRefs.
     *
     * @param refs the provided pageRefs (sorted)
     * @return the impacted scores
     */
    private List<Score> scoresOf (SortedSet<PageRef> refs)
    {
        final List<Score> impacted = new ArrayList<>();

        if (!refs.isEmpty()) {
            final int firstNumber = refs.first().getSheetNumber();
            final int lastNumber = refs.last().getSheetNumber();

            for (Score score : scores) {
                if (score.getLastPageRef().getSheetNumber() < firstNumber) {
                    continue;
                }

                if (score.getFirstPageRef().getSheetNumber() > lastNumber) {
                    break;
                }

                List<PageRef> scoreRefs = new ArrayList<>(score.getPageRefs());
                scoreRefs.retainAll(refs);

                if (!scoreRefs.isEmpty()) {
                    impacted.add(score);
                }
            }
        }

        return impacted;
    }

    //-----------------//
    // closeFileSystem //
    //-----------------//
    /**
     * Close the provided (book) file system.
     *
     * @param fileSystem the book file system
     */
    public static void closeFileSystem (FileSystem fileSystem)
    {
        try {
            fileSystem.close();

            logger.info("Book file system closed.");
        } catch (IOException ex) {
            logger.warn("Could not close book file system " + ex, ex);
        }
    }

    //-----//
    // ids //
    //-----//
    /**
     * Build a string with just the IDs of the stub collection.
     *
     * @param stubs the collection of stub instances
     * @return the string built
     */
    public static String ids (List<SheetStub> stubs)
    {
        if (stubs == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[");

        for (SheetStub entity : stubs) {
            sb.append("#").append(entity.getNumber());
        }

        sb.append("]");

        return sb.toString();
    }

    //----------//
    // loadBook //
    //----------//
    /**
     * Load a book out of a provided book file.
     *
     * @param bookPath path to the (zipped) book file
     * @return the loaded book if successful
     */
    public static Book loadBook (Path bookPath)
    {
        StopWatch watch = new StopWatch("loadBook " + bookPath);
        Book book = null;

        try {
            logger.info("Loading book {}", bookPath);
            watch.start("book");

            // Open book file
            Path rootPath = ZipFileSystem.open(bookPath);

            // Load book internals (just the stubs) out of book.xml
            Path internalsPath = rootPath.resolve(BOOK_INTERNALS);

            try (InputStream is = Files.newInputStream(internalsPath, StandardOpenOption.READ)) {
                JAXBContext ctx = getJaxbContext();
                Unmarshaller um = ctx.createUnmarshaller();
                ///Unmarshaller um = getJaxbContext().createUnmarshaller();
                book = (Book) um.unmarshal(is);
                LogUtil.start(book);
                book.getLock().lock();
                rootPath.getFileSystem().close(); // Close book file

                boolean ok = book.initTransients(null, bookPath);

                if (!ok) {
                    logger.info("Discarded {}", bookPath);

                    return null;
                }

                book.checkScore(); // TODO: remove ASAP

                return book;
            }
        } catch (IOException |
                 JAXBException ex) {
            logger.warn("Error loading book " + bookPath + " " + ex, ex);

            return null;
        } finally {
            if (constants.printWatch.isSet()) {
                watch.print();
            }

            if (book != null) {
                book.getLock().unlock();
            }

            LogUtil.stopBook();
        }
    }

    //--------------//
    // openBookFile //
    //--------------//
    /**
     * Open the book file (supposed to already exist at location provided by
     * '{@code bookPath}' parameter) for reading or writing.
     * <p>
     * When IO operations are finished, the book file must be closed via
     * {@link #closeFileSystem(java.nio.file.FileSystem)}
     *
     * @param bookPath book path name
     * @return the root path of the (zipped) book file system
     */
    public static Path openBookFile (Path bookPath)
    {
        if (bookPath == null) {
            throw new IllegalStateException("bookPath is null");
        }

        try {
            logger.debug("Book file system opened");

            FileSystem fileSystem = FileSystems.newFileSystem(bookPath);

            return fileSystem.getPath(fileSystem.getSeparator());
        } catch (FileNotFoundException ex) {
            logger.warn("File not found: " + bookPath, ex);
        } catch (IOException ex) {
            logger.warn("Error reading book:" + bookPath, ex);
        }

        return null;
    }

    //------------//
    // checkAlias //
    //------------//
    private static String checkAlias (Path path)
    {
        // Alias?
        if (AliasPatterns.useAliasPatterns()) {
            final String nameSansExt = FileUtil.getNameSansExtension(path);

            return BookManager.getInstance().getAlias(nameSansExt);
        }

        return null;
    }

    //----------------//
    // createBookFile //
    //----------------//
    /**
     * Create a new book file system dedicated to this book at the location provided
     * by '{@code bookpath}' member.
     * If such file already exists, it is deleted beforehand.
     * <p>
     * When IO operations are finished, the book file must be closed via
     * {@link #closeFileSystem(java.nio.file.FileSystem)}
     *
     * @return the root path of the (zipped) book file system
     */
    private static Path createBookFile (Path bookPath)
            throws IOException
    {
        if (bookPath == null) {
            throw new IllegalStateException("bookPath is null");
        }

        try {
            Files.deleteIfExists(bookPath);
        } catch (IOException ex) {
            logger.warn("Error deleting book: " + bookPath, ex);
        }

        // Make sure the containing folder exists
        Files.createDirectories(bookPath.getParent());

        // Make it a zip file
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(bookPath.toFile()));
        zos.close();

        // Finally open the book file just created
        return ZipFileSystem.open(bookPath);
    }

    //----------------//
    // getJaxbContext //
    //----------------//
    private static JAXBContext getJaxbContext ()
            throws JAXBException
    {
        // Lazy creation
        if (jaxbContext == null) {
            jaxbContext = JAXBContext.newInstance(Book.class, RunTable.class);
        }

        return jaxbContext;
    }

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {

        private final Constant.Boolean printWatch = new Constant.Boolean(
                false,
                "Should we print out the stop watch for book loading?");

        private final Constant.Boolean processAllStubsInParallel = new Constant.Boolean(
                false,
                "Should we process all stubs of a book in parallel? (beware of many stubs)");

        private final Constant.Boolean checkBookVersion = new Constant.Boolean(
                true,
                "Should we check version of loaded book files?");

        private final Constant.Boolean resetOldBooks = new Constant.Boolean(
                true,
                "Should we reset to binary the too old book files?");
    }

    //------------------//
    // OcrBookLanguages //
    //------------------//
    private static class OcrBookLanguages
            extends Param<String>
    {

        @Override
        public boolean setSpecific (String specific)
        {
            if ((specific != null) && specific.isEmpty()) {
                specific = null;
            }

            return super.setSpecific(specific);
        }

        /**
         * JAXB adapter to mimic XmlValue.
         */
        public static class Adapter
                extends XmlAdapter<String, OcrBookLanguages>
        {

            @Override
            public String marshal (OcrBookLanguages val)
                    throws Exception
            {
                if (val == null) {
                    return null;
                }

                return val.getSpecific();
            }

            @Override
            public OcrBookLanguages unmarshal (String str)
                    throws Exception
            {
                OcrBookLanguages ol = new OcrBookLanguages();
                ol.setSpecific(str);

                return ol;
            }
        }
    }
}
