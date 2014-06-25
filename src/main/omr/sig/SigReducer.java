//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                       S i g R e d u c e r                                      //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Herve Bitteur and others 2000-2014. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sig;

import omr.glyph.Shape;
import omr.glyph.ShapeSet;

import omr.grid.StaffInfo;

import omr.math.GeoOrder;
import omr.math.GeoUtil;

import omr.sheet.Scale;
import omr.sheet.SystemInfo;

import omr.sig.SIGraph.ReductionMode;
import static omr.sig.StemPortion.*;

import omr.util.HorizontalSide;
import static omr.util.HorizontalSide.*;
import omr.util.Navigable;
import omr.util.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

/**
 * Class {@code SigReducer} deals with SIG reduction.
 *
 * @author Hervé Bitteur
 */
public class SigReducer
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(SigReducer.class);

    /** Shapes for which overlap detection is (currently) disabled. */
    private static final EnumSet disabledShapes = EnumSet.copyOf(
            Arrays.asList(Shape.LEDGER, Shape.CRESCENDO, Shape.DECRESCENDO, Shape.SLUR));

    /** Shapes that can overlap with a beam. */
    private static final EnumSet beamCompShapes = EnumSet.copyOf(
            Arrays.asList(
                    Shape.THICK_BARLINE,
                    Shape.THICK_CONNECTION,
                    Shape.THIN_BARLINE,
                    Shape.THIN_CONNECTION));

    /** Shapes that can overlap with a stem. */
    private static final EnumSet stemCompShapes = EnumSet.copyOf(
            Arrays.asList(Shape.SLUR, Shape.CRESCENDO, Shape.DECRESCENDO));

    //~ Instance fields ----------------------------------------------------------------------------
    /** The dedicated system */
    @Navigable(false)
    private final SystemInfo system;

    /** Scale. */
    @Navigable(false)
    private final Scale scale;

    /** The related SIG. */
    private final SIGraph sig;

    //~ Constructors -------------------------------------------------------------------------------
    //------------//
    // SigReducer //
    //------------//
    /**
     * Creates a new SigReducer object.
     *
     * @param system the related system
     * @param sig    the system SIG
     */
    public SigReducer (SystemInfo system,
                       SIGraph sig)
    {
        this.system = system;
        this.sig = sig;
        scale = system.getSheet().getScale();
    }

    //~ Methods ------------------------------------------------------------------------------------
    //---------------//
    // contextualize //
    //---------------//
    /**
     * Compute contextual grades of interpretations based on their supporting partners.
     */
    public void contextualize ()
    {
        try {
            for (Inter inter : sig.vertexSet()) {
                sig.computeContextualGrade(inter, false);
            }
        } catch (Throwable ex) {
            ex.printStackTrace();
        }
    }

    //--------//
    // reduce //
    //--------//
    /**
     * Reduce all the interpretations and relations of the SIG.
     *
     * @param mode selected reduction mode
     */
    public void reduce (ReductionMode mode)
    {
        final boolean logging = false;

        if (logging) {
            logger.info("S#{} reducing sig ...", system.getId());
        }

        // General overlap checks
        detectOverlaps();
        detectHeadInconsistency();

        int modifs; // modifications done in current iteration
        int reductions; // Count of reductions performed
        int deletions; // Count of deletions performed

        do {
            // First, remove all inters with too low contextual grade
            deletions = purgeWeakInters();

            do {
                modifs = 0;
                // Detect lack of mandatory support relation for certain inters
                modifs += checkHeads();
                deletions += purgeWeakInters();

                modifs += checkFlags();
                deletions += purgeWeakInters();

                modifs += checkBeams();
                deletions += purgeWeakInters();

                modifs += checkHooks();
                deletions += purgeWeakInters();

                modifs += checkLedgers();
                deletions += purgeWeakInters();

                modifs += checkStems();
                deletions += purgeWeakInters();

                modifs += checkRepeatDots();
                modifs += checkAugmentationDots();
                modifs += checkAugmented();
                deletions += purgeWeakInters();

                if (logging) {
                    logger.info("S#{} modifs: {}", system.getId(), modifs);
                }
            } while (modifs > 0);

            // Remaining exclusions
            reductions = sig.reduceExclusions(mode).size();

            if (logging) {
                logger.info("S#{} reductions: {}", system.getId(), reductions);
            }
        } while ((reductions > 0) || (deletions > 0));
    }

    //---------------------//
    // reduceAugmentations //
    //---------------------//
    /**
     * Reduce the number of augmentation relations to one.
     *
     * @param rels the augmentation links for the same entity
     * @return the number of relation deleted
     */
    int reduceAugmentations (Set<Relation> rels)
    {
        int modifs = 0;

        // Simply select the relation with best grade
        double bestGrade = 0;
        AbstractConnection bestLink = null;

        for (Relation rel : rels) {
            AbstractConnection link = (AbstractConnection) rel;
            double grade = link.getGrade();

            if (grade > bestGrade) {
                bestGrade = grade;
                bestLink = link;
            }
        }

        for (Relation rel : rels) {
            if (rel != bestLink) {
                sig.removeEdge(rel);
                modifs++;
            }
        }

        return modifs;
    }

    //------------------//
    // beamHasBothStems //
    //------------------//
    private boolean beamHasBothStems (FullBeamInter beam)
    {
        boolean hasLeft = false;
        boolean hasRight = false;

        //        if (beam.isVip()) {
        //            logger.info("VIP beamHasBothStems for {}", beam);
        //        }
        //
        for (Relation rel : sig.edgesOf(beam)) {
            if (rel instanceof BeamStemRelation) {
                BeamStemRelation bsRel = (BeamStemRelation) rel;
                BeamPortion portion = bsRel.getBeamPortion();

                if (portion == BeamPortion.LEFT) {
                    hasLeft = true;
                } else if (portion == BeamPortion.RIGHT) {
                    hasRight = true;
                }
            }
        }

        return hasLeft && hasRight;
    }

    //-----------------------//
    // checkAugmentationDots //
    //-----------------------//
    /**
     * Perform checks on augmentation dots.
     * <p>
     * An augmentation dot needs a target to augment (note, rest) or another augmentation dot.
     *
     * @return the count of modifications done
     */
    private int checkAugmentationDots ()
    {
        int modifs = 0;
        List<Inter> dots = sig.inters(AugmentationDotInter.class);

        for (Iterator<Inter> it = dots.iterator(); it.hasNext();) {
            AugmentationDotInter dot = (AugmentationDotInter) it.next();

            if (!dotHasAugmentationTarget(dot)) {
                if (dot.isVip() || logger.isDebugEnabled()) {
                    logger.info("Deleting augmentation dot lacking target {}", dot);
                }

                sig.removeVertex(dot);
                it.remove();
                modifs++;
            }
        }

        return modifs;
    }

    //----------------//
    // checkAugmented //
    //----------------//
    /**
     * Perform checks on augmented entities.
     * <p>
     * An entity (note, rest or augmentation dot) can have at most one augmentation dot.
     *
     * @return the count of modifications done
     */
    private int checkAugmented ()
    {
        int modifs = 0;
        List<Inter> entities = sig.inters(AbstractNoteInter.class);
        entities.addAll(sig.inters(RestInter.class));

        for (Inter entity : entities) {
            Set<Relation> rels = sig.getRelations(entity, AugmentationRelation.class);

            if (rels.size() > 1) {
                modifs += reduceAugmentations(rels);

                if (entity.isVip() || logger.isDebugEnabled()) {
                    logger.info("Reduced augmentations for {}", entity);
                }
            }
        }

        return modifs;
    }

    //------------//
    // checkBeams //
    //------------//
    /**
     * Perform checks on beams.
     *
     * @return the count of modifications done
     */
    private int checkBeams ()
    {
        int modifs = 0;
        List<Inter> beams = sig.inters(FullBeamInter.class);

        for (Iterator<Inter> it = beams.iterator(); it.hasNext();) {
            FullBeamInter beam = (FullBeamInter) it.next();

            if (!beamHasBothStems(beam)) {
                if (beam.isVip() || logger.isDebugEnabled()) {
                    logger.info("VIP Deleting beam lacking stem {}", beam);
                }

                sig.removeVertex(beam);
                it.remove();
                modifs++;
            }
        }

        return modifs;
    }

    //------------//
    // checkFlags //
    //------------//
    /**
     * Perform checks on flags.
     *
     * @return the count of modifications done
     */
    private int checkFlags ()
    {
        int modifs = 0;
        final List<Inter> flags = sig.inters(ShapeSet.Flags.getShapes());

        for (Iterator<Inter> it = flags.iterator(); it.hasNext();) {
            final Inter flag = it.next();

            if (!flagHasStem(flag)) {
                if (flag.isVip() || logger.isDebugEnabled()) {
                    logger.info("No stem for {}", flag);
                }

                sig.removeVertex(flag);
                it.remove();
                modifs++;

                continue;
            }
        }

        return modifs;
    }

    //---------------//
    // checkHeadSide //
    //---------------//
    /**
     * If head is on the wrong side of the stem, check if there is a
     * head on the other side, located one or two step(s) further.
     * <p>
     * If the side is wrong and there is no head on the other side, simply remove this head-stem
     * relation and insert exclusion instead.
     *
     * @param head the head inter (black or void)
     * @return the number of modifications done
     */
    private int checkHeadSide (Inter head)
    {
        if (head.isVip()) {
            logger.info("VIP checkHeadSide for {}", head);
        }

        int modifs = 0;

        // Check all connected stems
        Set<Relation> stemRels = sig.getRelations(head, HeadStemRelation.class);

        RelsLoop:
        for (Relation relation : stemRels) {
            HeadStemRelation rel = (HeadStemRelation) relation;
            StemInter stem = (StemInter) sig.getEdgeTarget(rel);

            // What is the stem direction? (up: dir < 0, down: dir > 0)
            int dir = stemDirection(stem);

            if (dir == 0) {
                continue; // We cannot check
            }

            // Side is normal?
            HorizontalSide headSide = rel.getHeadSide();

            if (((headSide == LEFT) && (dir > 0)) || ((headSide == RIGHT) && (dir < 0))) {
                continue; // It's OK
            }

            // Pitch of the note head
            int pitch = ((AbstractNoteInter) head).getPitch();

            // Target side and target pitches of other head
            // Look for presence of head on other side with target pitch
            HorizontalSide targetSide = headSide.opposite();

            for (int targetPitch = pitch - 1; targetPitch <= (pitch + 1); targetPitch++) {
                if (stem.lookupHead(targetSide, targetPitch) != null) {
                    continue RelsLoop; // OK
                }
            }

            // We have a bad head+stem couple, so let's remove the relationship
            if (head.isVip() || logger.isDebugEnabled()) {
                logger.info("Wrong side for {} on {}", head, stem);
            }

            sig.removeEdge(rel);
            sig.insertExclusion(head, stem, Exclusion.Cause.INCOMPATIBLE);
            modifs++;
        }

        return modifs;
    }

    //------------//
    // checkHeads //
    //------------//
    /**
     * Perform checks on heads.
     *
     * @return the count of modifications done
     */
    private int checkHeads ()
    {
        int modifs = 0;
        final List<Inter> heads = sig.inters(ShapeSet.NoteHeads.getShapes());

        for (Iterator<Inter> it = heads.iterator(); it.hasNext();) {
            final Inter head = it.next();

            if (!headHasStem(head)) {
                if (head.isVip() || logger.isDebugEnabled()) {
                    logger.info("No stem for {}", head);
                }

                sig.removeVertex(head);
                it.remove();
                modifs++;

                continue;
            }

            modifs += checkHeadSide(head);
        }

        return modifs;
    }

    //------------//
    // checkHooks //
    //------------//
    /**
     * Perform checks on beam hooks.
     *
     * @return the count of modifications done
     */
    private int checkHooks ()
    {
        int modifs = 0;
        final List<Inter> flags = sig.inters(ShapeSet.Flags.getShapes());

        for (Iterator<Inter> it = flags.iterator(); it.hasNext();) {
            final Inter flag = it.next();

            if (!flagHasStem(flag)) {
                if (flag.isVip() || logger.isDebugEnabled()) {
                    logger.info("No stem for {}", flag);
                }

                sig.removeVertex(flag);
                it.remove();
                modifs++;

                continue;
            }
        }

        return modifs;
    }

    //--------------//
    // checkLedgers //
    //--------------//
    /**
     * Perform checks on ledger.
     *
     * @return the count of modifications done
     */
    private int checkLedgers ()
    {
        // All system notes, sorted by abscissa
        List<Inter> allNotes = sig.inters(
                ShapeSet.shapesOf(ShapeSet.NoteHeads.getShapes(), ShapeSet.Notes.getShapes()));
        Collections.sort(allNotes, Inter.byAbscissa);

        int modifs = 0;
        boolean modified;

        do {
            modified = false;

            for (StaffInfo staff : system.getStaves()) {
                SortedMap<Integer, SortedSet<LedgerInter>> map = staff.getLedgerMap();

                for (Entry<Integer, SortedSet<LedgerInter>> entry : map.entrySet()) {
                    int index = entry.getKey();
                    SortedSet<LedgerInter> ledgers = entry.getValue();
                    List<LedgerInter> toRemove = new ArrayList<LedgerInter>();

                    for (LedgerInter ledger : ledgers) {
                        if (ledger.isVip()) {
                            logger.info("VIP ledger {}", ledger);
                        }

                        if (!ledgerHasNoteOrLedger(staff, index, ledger, allNotes)) {
                            if (ledger.isVip() || logger.isDebugEnabled()) {
                                logger.info("Deleting orphan ledger {}", ledger);
                            }

                            sig.removeVertex(ledger);
                            toRemove.add(ledger);
                            modified = true;
                            modifs++;
                        }
                    }

                    if (!toRemove.isEmpty()) {
                        ledgers.removeAll(toRemove);
                    }
                }
            }
        } while (modified);

        return modifs;
    }

    //-----------------//
    // checkRepeatDots //
    //-----------------//
    /**
     * Perform checks on repeat dots
     *
     * @return the count of modifications done
     */
    private int checkRepeatDots ()
    {
        int modifs = 0;
        List<Inter> dots = sig.inters(RepeatDotInter.class);

        for (Iterator<Inter> it = dots.iterator(); it.hasNext();) {
            RepeatDotInter dot = (RepeatDotInter) it.next();

            if (!dotHasSibling(dot)) {
                if (dot.isVip() || logger.isDebugEnabled()) {
                    logger.info("Deleting repeat dot lacking sibling {}", dot);
                }

                sig.removeVertex(dot);
                it.remove();
                modifs++;
            }
        }

        return modifs;
    }

    //------------//
    // checkStems //
    //------------//
    /**
     * Perform checks on stems.
     *
     * @return the count of modifications done
     */
    private int checkStems ()
    {
        int modifs = 0;
        List<Inter> stems = sig.inters(Shape.STEM);

        for (Iterator<Inter> it = stems.iterator(); it.hasNext();) {
            StemInter stem = (StemInter) it.next();

            if (!stemHasHeadAtEnd(stem)) {
                if (stem.isVip() || logger.isDebugEnabled()) {
                    logger.info("Deleting stem lacking starting head {}", stem);
                }

                sig.removeVertex(stem);
                it.remove();
                modifs++;

                continue;
            }

            if (!stemHasSingleHeadEnd(stem)) {
                modifs++;
            }
        }

        return modifs;
    }

    //------------//
    // compatible //
    //------------//
    /**
     * Check whether the two provided Inter instance can overlap.
     *
     * @param inters array of exactly 2 instances
     * @return true if overlap is accepted, false otherwise
     */
    private boolean compatible (Inter[] inters)
    {
        for (int i = 0; i <= 1; i++) {
            if (inters[i] instanceof AbstractBeamInter) {
                Inter other = inters[1 - i];

                if (other instanceof AbstractBeamInter) {
                    return true;
                }

                if (beamCompShapes.contains(other.getShape())) {
                    return true;
                }
            }

            if (inters[i] instanceof StemInter) {
                Inter other = inters[1 - i];

                if (stemCompShapes.contains(other.getShape())) {
                    return true;
                }
            }
        }

        return false;
    }

    //-------------------------//
    // detectHeadInconsistency //
    //-------------------------//
    /**
     * Detect inconsistency of note heads attached to a (good) stem.
     */
    private void detectHeadInconsistency ()
    {
        // All stems of the sig
        List<Inter> stems = sig.inters(Shape.STEM);

        // Heads organized by class (black, void, and small versions)
        Map<Class, Set<Inter>> heads = new HashMap<Class, Set<Inter>>();

        for (Inter si : stems) {
            if (!si.isGood()) {
                continue;
            }

            heads.clear();

            for (Relation rel : sig.edgesOf(si)) {
                if (rel instanceof HeadStemRelation) {
                    Inter head = sig.getEdgeSource(rel);
                    Class classe = head.getClass();
                    Set<Inter> set = heads.get(classe);

                    if (set == null) {
                        heads.put(classe, set = new HashSet<Inter>());
                    }

                    set.add(head);
                }
            }

            List<Class> clist = new ArrayList<Class>(heads.keySet());

            for (int ic = 0; ic < (clist.size() - 1); ic++) {
                Class c1 = clist.get(ic);
                Set set1 = heads.get(c1);

                for (Class c2 : clist.subList(ic + 1, clist.size())) {
                    Set set2 = heads.get(c2);
                    exclude(set1, set2);
                }
            }
        }
    }

    //----------------//
    // detectOverlaps //
    //----------------//
    /**
     * (Prototype).
     */
    private void detectOverlaps ()
    {
        // Take all inters except ledgers (and perhaps others, TODO)
        List<Inter> inters = sig.inters(
                new Predicate<Inter>()
                {
                    @Override
                    public boolean check (Inter inter)
                    {
                        return !disabledShapes.contains(inter.getShape());
                    }
                });

        Collections.sort(inters, Inter.byAbscissa);

        for (int i = 0, iBreak = inters.size() - 1; i < iBreak; i++) {
            Inter left = inters.get(i);
            Rectangle leftBox = left.getBounds();
            double xMax = leftBox.getMaxX();

            for (Inter right : inters.subList(i + 1, inters.size())) {
                // Overlap test beam/beam doesn't work (and is useless in fact)
                if (compatible(new Inter[]{left, right})) {
                    continue;
                }

                Rectangle rightBox = right.getBounds();

                if (leftBox.intersects(rightBox)) {
                    // Have a more precise look
                    if (left.overlaps(right)) {
                        // If there is no relation between left & right insert an exclusion
                        Set<Relation> rels1 = sig.getAllEdges(left, right);
                        Set<Relation> rels2 = sig.getAllEdges(right, left);

                        if (rels1.isEmpty() && rels2.isEmpty()) {
                            sig.insertExclusion(left, right, Exclusion.Cause.OVERLAP);
                        }
                    }
                } else if (rightBox.x > xMax) {
                    break;
                }
            }
        }
    }

    //--------------------------//
    // dotHasAugmentationTarget //
    //--------------------------//
    /**
     * Check whether the augmentation dot has a target (note or rest or other dot)
     *
     * @param dot the augmentation dot inter
     * @return true if OK
     */
    private boolean dotHasAugmentationTarget (AugmentationDotInter dot)
    {
        for (Relation rel : sig.edgesOf(dot)) {
            if (rel instanceof AugmentationRelation) {
                return true;
            }

            if (rel instanceof DoubleDotRelation) {
                return true;
            }
        }

        return false;
    }

    //---------------//
    // dotHasSibling //
    //---------------//
    /**
     * Check if the repeat dot has a sibling dot.
     *
     * @param dot the repeat dot inter
     * @return true if OK
     */
    private boolean dotHasSibling (Inter dot)
    {
        for (Relation rel : sig.edgesOf(dot)) {
            if (rel instanceof RepeatDotDotRelation) {
                return true;
            }
        }

        return false;
    }

    //---------//
    // exclude //
    //---------//
    private void exclude (Set<Inter> set1,
                          Set<Inter> set2)
    {
        for (Inter i1 : set1) {
            for (Inter i2 : set2) {
                sig.insertExclusion(i1, i2, Exclusion.Cause.INCOMPATIBLE);
            }
        }
    }

    //-------------//
    // flagHasStem //
    //-------------//
    /**
     * Check if the flag has a stem relation.
     *
     * @param flag the flag inter
     * @return true if OK
     */
    private boolean flagHasStem (Inter flag)
    {
        for (Relation rel : sig.edgesOf(flag)) {
            if (rel instanceof FlagStemRelation) {
                return true;
            }
        }

        return false;
    }

    //-------------//
    // headHasStem //
    //-------------//
    /**
     * Check if the head has a stem relation.
     *
     * @param head the head inter (black of void)
     * @return true if OK
     */
    private boolean headHasStem (Inter head)
    {
        for (Relation rel : sig.edgesOf(head)) {
            if (rel instanceof HeadStemRelation) {
                return true;
            }
        }

        return false;
    }

    //-------------//
    // hookHasStem //
    //-------------//
    /**
     * Check if a beam hook has a stem.
     */
    private boolean hookHasStem (BeamHookInter hook)
    {
        boolean hasLeft = false;
        boolean hasRight = false;

        if (hook.isVip()) {
            logger.info("VIP hookHasStem for {}", hook);
        }

        for (Relation rel : sig.edgesOf(hook)) {
            if (rel instanceof BeamStemRelation) {
                BeamStemRelation bsRel = (BeamStemRelation) rel;
                BeamPortion portion = bsRel.getBeamPortion();

                if (portion == BeamPortion.LEFT) {
                    hasLeft = true;
                } else if (portion == BeamPortion.RIGHT) {
                    hasRight = true;
                }
            }
        }

        return hasLeft || hasRight;
    }

    //-----------------------//
    // ledgerHasNoteOrLedger //
    //-----------------------//
    /**
     * Check if the provided ledger has either a note centered on it
     * (or one step further) or another ledger right further.
     *
     * @param staff    the containing staff
     * @param index    the ledger line index
     * @param ledger   the ledger to check
     * @param allNotes the abscissa-ordered list of notes in the system
     * @return true if OK
     */
    private boolean ledgerHasNoteOrLedger (StaffInfo staff,
                                           int index,
                                           LedgerInter ledger,
                                           List<Inter> allNotes)
    {
        Rectangle ledgerBox = new Rectangle(ledger.getBounds());
        ledgerBox.grow(0, scale.getInterline()); // Very high box, but that's OK

        // Check for another ledger on next line
        int nextIndex = index + Integer.signum(index);
        SortedSet<LedgerInter> nextLedgers = staff.getLedgers(nextIndex);

        if (nextLedgers != null) {
            for (LedgerInter nextLedger : nextLedgers) {
                // Check abscissa compatibility
                if (GeoUtil.xOverlap(ledgerBox, nextLedger.getBounds()) > 0) {
                    return true;
                }
            }
        }

        // Else, check for a note centered on ledger, or just on next pitch
        final int ledgerPitch = StaffInfo.getLedgerPitchPosition(index);
        final int nextPitch = ledgerPitch + Integer.signum(index);

        final List<Inter> notes = sig.intersectedInters(allNotes, GeoOrder.BY_ABSCISSA, ledgerBox);

        for (Inter inter : notes) {
            final AbstractNoteInter note = (AbstractNoteInter) inter;
            final int notePitch = note.getPitch();

            if ((notePitch == ledgerPitch) || (notePitch == nextPitch)) {
                return true;
            }
        }

        return false;
    }

    //    //------------------//
    //    // lookupExclusions //
    //    //------------------//
    //    private int lookupExclusions ()
    //    {
    //        // Deletions
    //        Set<Inter> toRemove = new HashSet<Inter>();
    //
    //        for (Relation rel : sig.edgeSet()) {
    //            if (rel instanceof Exclusion) {
    //                final Inter source = sig.getEdgeSource(rel);
    //                final double scp = source.getContextualGrade();
    //                final Inter target = sig.getEdgeTarget(rel);
    //                final double tcp = target.getContextualGrade();
    //                Inter weaker = (scp < tcp) ? source : target;
    //
    //                if (weaker.isVip()) {
    //                    logger.info("Remaining {} deleting weaker {}", rel.toLongString(sig), weaker);
    //                }
    //
    //                toRemove.add(weaker);
    //            }
    //        }
    //
    //        for (Inter inter : toRemove) {
    //            sig.removeVertex(inter);
    //        }
    //
    //        return toRemove.size();
    //    }
    //
    //-----------------//
    // purgeWeakInters //
    //-----------------//
    private int purgeWeakInters ()
    {
        contextualize();

        return sig.deleteWeakInters().size();
    }

    //--------------//
    // sortBySource //
    //--------------//
    /**
     * Sort the provided list of relations by decreasing contextual grade of the
     * relations sources.
     *
     * @param rels the relations to sort
     */
    private void sortBySource (List<Relation> rels)
    {
        Collections.sort(
                rels,
                new Comparator<Relation>()
                {
                    @Override
                    public int compare (Relation r1,
                                        Relation r2)
                    {
                        Inter s1 = sig.getEdgeSource(r1);
                        Inter s2 = sig.getEdgeSource(r2);

                        return Double.compare(s2.getContextualGrade(), s1.getContextualGrade());
                    }
                });
    }

    //---------------//
    // stemDirection //
    //---------------//
    /**
     * Report the direction (from head to tail) of the provided stem.
     * <p>
     * For this, we check what is found on each stem end (is it a tail: beam/flag or is it a head)
     * and use contextual grade to choose the best reference.
     *
     * @param stem the stem to check
     * @return -1 for stem up, +1 for stem down, 0 for unknown
     */
    private int stemDirection (StemInter stem)
    {
        final Line2D stemLine = sig.getStemLine(stem);
        final List<Relation> links = new ArrayList<Relation>(
                sig.getRelations(stem, StemConnection.class));
        sortBySource(links);

        for (Relation rel : links) {
            Inter source = sig.getEdgeSource(rel); // Source is a head, a beam or a flag

            // Retrieve the stem portion for this link
            if (rel instanceof HeadStemRelation) {
                // Head -> Stem
                HeadStemRelation link = (HeadStemRelation) rel;
                StemPortion portion = link.getStemPortion(source, stemLine, scale);

                if (portion == STEM_BOTTOM) {
                    if (link.getHeadSide() == RIGHT) {
                        return -1;
                    }
                } else if (portion == STEM_TOP) {
                    if (link.getHeadSide() == LEFT) {
                        return 1;
                    }
                }
            } else {
                // Tail (Beam or Flag) -> Stem
                if (rel instanceof BeamStemRelation) {
                    // Beam -> Stem
                    BeamStemRelation link = (BeamStemRelation) rel;
                    StemPortion portion = link.getStemPortion(source, stemLine, scale);

                    return (portion == STEM_TOP) ? (-1) : 1;
                } else {
                    // Flag -> Stem
                    FlagStemRelation link = (FlagStemRelation) rel;
                    StemPortion portion = link.getStemPortion(source, stemLine, scale);

                    if (portion == STEM_TOP) {
                        return -1;
                    }

                    if (portion == STEM_BOTTOM) {
                        return 1;
                    }
                }
            }
        }

        return 0; // Cannot decide!
    }

    //------------------//
    // stemHasHeadAtEnd //
    //------------------//
    /**
     * Check if the stem has at least a head at some end.
     *
     * @param stem the stem inter
     * @return true if OK
     */
    private boolean stemHasHeadAtEnd (StemInter stem)
    {
        if (stem.isVip()) {
            logger.info("VIP stemHasHeadAtEnd for {}", stem);
        }

        final Line2D stemLine = sig.getStemLine(stem);

        for (Relation rel : sig.getRelations(stem, HeadStemRelation.class)) {
            // Check stem portion
            HeadStemRelation hsRel = (HeadStemRelation) rel;
            Inter head = sig.getOppositeInter(stem, rel);

            if (hsRel.getStemPortion(head, stemLine, scale) != STEM_MIDDLE) {
                return true;
            }
        }

        return false;
    }

    //----------------------//
    // stemHasSingleHeadEnd //
    //----------------------//
    /**
     * Check if the stem does not have heads at both ends.
     * <p>
     * If heads are found at the "tail side" of the stem, their relations to the stem are removed
     * (TODO: and replaced by exclusions?).
     *
     * @param stem the stem inter
     * @return true if OK
     */
    private boolean stemHasSingleHeadEnd (StemInter stem)
    {
        final Line2D stemLine = sig.getStemLine(stem);
        final int stemDir = stemDirection(stem);

        if (stemDir == 0) {
            return true; // We cannot decide
        }

        final StemPortion forbidden = (stemDir > 0) ? STEM_BOTTOM : STEM_TOP;
        final List<Relation> toRemove = new ArrayList<Relation>();

        for (Relation rel : sig.getRelations(stem, HeadStemRelation.class)) {
            // Check stem portion
            HeadStemRelation hsRel = (HeadStemRelation) rel;
            Inter head = sig.getOppositeInter(stem, rel);
            StemPortion portion = hsRel.getStemPortion(head, stemLine, scale);

            if (portion == forbidden) {
                if (stem.isVip() || logger.isDebugEnabled()) {
                    logger.info("Cutting relation between {} and {}", stem, sig.getEdgeSource(rel));
                }

                toRemove.add(rel);
            }
        }

        if (!toRemove.isEmpty()) {
            sig.removeAllEdges(toRemove);
        }

        return toRemove.isEmpty();
    }
}
