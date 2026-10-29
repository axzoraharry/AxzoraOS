package com.axzora.happyreddy;

import java.util.ArrayList;
import java.util.List;

/**
 * A self-contained chess engine so Mr Happy can actually PLAY chess by voice.
 *
 * 0x88 board, full legal move generation (castling, en passant, promotion),
 * check / checkmate / stalemate detection, and an alpha-beta search with a
 * material + piece-square evaluation. One game is kept in memory (static),
 * which is all a single voice opponent needs.
 *
 * Moves are spoken/typed in coordinate form: "e2e4", "e7e5", "e1g1" (castle),
 * "e7e8q" (promote). Mr Happy calls play_chess("new"|"move"|"board"),
 * applies the user's move, then replies with its own move and the board.
 */
final class ChessEngine {

    // Piece codes: white positive, black negative.
    private static final int EMPTY = 0, P = 1, N = 2, B = 3, R = 4, Q = 5, K = 6;

    // 0x88 board: index = rank*16 + file. A square is on-board iff (i & 0x88)==0.
    private static int[] board = new int[128];
    private static boolean whiteToMove = true;
    private static int epSquare = -1;                 // en-passant target, else -1
    private static boolean wK = true, wQ = true, bK = true, bQ = true; // castling
    private static boolean started = false;
    private static int searchDepth = 3;               // plies of look-ahead

    private ChessEngine() { }

    // ── Public API used by DeviceActions.play_chess ──────────────────────────

    /** Start a fresh game. userIsWhite=true → user moves first. */
    static synchronized String newGame(boolean userIsWhite) {
        setupStart();
        started = true;
        String s = "New game. You are " + (userIsWhite ? "White" : "Black") + ".\n" + ascii();
        if (!userIsWhite) {
            // Engine (White) opens.
            int[] m = bestMove();
            if (m != null) { s = "New game. You are Black. My move: " + moveStr(m) + ".\n"
                    + applyAndReport(m); }
        }
        return s;
    }

    static synchronized String boardText() {
        if (!started) return "No game yet. Say 'start a new chess game' first.";
        return (whiteToMove ? "White" : "Black") + " to move.\n" + ascii();
    }

    /** Apply the user's move (coordinate form), then reply with the engine's move. */
    static synchronized String userMove(String uci) {
        if (!started) { setupStart(); started = true; }
        if (uci == null) return "Tell me your move, like 'e2e4'.";
        uci = uci.trim().toLowerCase().replaceAll("[^a-h0-9qrbnk]", "");
        int[] mv = parse(uci);
        if (mv == null) return "I couldn't read '" + uci + "'. Use squares like 'e2e4'.";
        List<int[]> legal = legalMoves();
        int[] chosen = null;
        for (int[] m : legal) {
            if (m[0] == mv[0] && m[1] == mv[1]
                    && (mv[2] == 0 || m[2] == mv[2])) { chosen = m; break; }
        }
        if (chosen == null) return "That's not a legal move. " + statusLine()
                + " Your legal moves include: " + sampleMoves(legal);

        String userSide = whiteToMove ? "White" : "Black";
        make(chosen);
        String afterUser = terminalOrNull();
        if (afterUser != null) return userSide + " plays " + moveStr(chosen) + ". " + afterUser + "\n" + ascii();

        int[] reply = bestMove();
        if (reply == null) return userSide + " plays " + moveStr(chosen) + ". " + statusLine() + "\n" + ascii();
        String mySide = whiteToMove ? "White" : "Black";
        make(reply);
        String afterMe = terminalOrNull();
        String head = userSide + " plays " + moveStr(chosen) + ". I ("
                + mySide + ") play " + moveStr(reply) + ".";
        return head + (afterMe != null ? " " + afterMe : " " + statusLine()) + "\n" + ascii();
    }

    // ── Terminal state ───────────────────────────────────────────────────────

    private static String terminalOrNull() {
        List<int[]> legal = legalMoves();
        if (!legal.isEmpty()) return inCheck(whiteToMove) ? "Check!" : null;
        if (inCheck(whiteToMove))
            return "Checkmate — " + (whiteToMove ? "Black" : "White") + " wins!";
        return "Stalemate — it's a draw.";
    }

    private static String statusLine() {
        return (whiteToMove ? "White" : "Black") + " to move"
                + (inCheck(whiteToMove) ? " (in check)." : ".");
    }

    private static String applyAndReport(int[] m) {
        make(m);
        String t = terminalOrNull();
        return (t != null ? t + "\n" : "") + ascii();
    }

    private static String sampleMoves(List<int[]> legal) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, legal.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(moveStr(legal.get(i)));
        }
        return sb.toString();
    }

    // ── Board setup ──────────────────────────────────────────────────────────

    private static void setupStart() {
        board = new int[128];
        int[] backrank = {R, N, B, Q, K, B, N, R};
        for (int f = 0; f < 8; f++) {
            board[sq(f, 0)] = backrank[f];
            board[sq(f, 1)] = P;
            board[sq(f, 6)] = -P;
            board[sq(f, 7)] = -backrank[f];
        }
        whiteToMove = true; epSquare = -1;
        wK = wQ = bK = bQ = true;
    }

    private static int sq(int file, int rank) { return rank * 16 + file; }
    private static int file(int s) { return s & 7; }
    private static int rank(int s) { return s >> 4; }
    private static boolean on(int s) { return (s & 0x88) == 0; }

    // ── Move generation ──────────────────────────────────────────────────────

    private static final int[] KN = {31, 33, 14, 18, -31, -33, -14, -18};
    private static final int[] KG = {1, -1, 16, -16, 15, 17, -15, -17};
    private static final int[] BISHOP = {15, 17, -15, -17};
    private static final int[] ROOK = {1, -1, 16, -16};

    /** Pseudo-legal moves for the side to move. Each move: {from,to,promo,flag}. */
    private static List<int[]> pseudoMoves() {
        List<int[]> out = new ArrayList<>();
        int side = whiteToMove ? 1 : -1;
        for (int s = 0; s < 128; s++) {
            if (!on(s)) continue;
            int pc = board[s];
            if (pc == 0 || Integer.signum(pc) != side) continue;
            int t = Math.abs(pc);
            switch (t) {
                case P: pawnMoves(s, side, out); break;
                case N: for (int d : KN) step(s, d, side, false, out); break;
                case B: for (int d : BISHOP) step(s, d, side, true, out); break;
                case R: for (int d : ROOK) step(s, d, side, true, out); break;
                case Q: for (int d : KG) step(s, d, side, true, out); break;
                case K:
                    for (int d : KG) step(s, d, side, false, out);
                    castleMoves(s, side, out);
                    break;
            }
        }
        return out;
    }

    private static void step(int from, int d, int side, boolean slide, List<int[]> out) {
        int to = from + d;
        while (on(to)) {
            int occ = board[to];
            if (occ == 0) { out.add(new int[]{from, to, 0, 0}); }
            else { if (Integer.signum(occ) != side) out.add(new int[]{from, to, 0, 0}); break; }
            if (!slide) break;
            to += d;
        }
    }

    private static void pawnMoves(int s, int side, List<int[]> out) {
        int dir = 16 * side;
        int startRank = side == 1 ? 1 : 6;
        int promoRank = side == 1 ? 7 : 0;
        int one = s + dir;
        if (on(one) && board[one] == 0) {
            addPawn(s, one, promoRank, out);
            int two = s + 2 * dir;
            if (rank(s) == startRank && board[two] == 0) out.add(new int[]{s, two, 0, 2});
        }
        for (int dc : new int[]{dir - 1, dir + 1}) {
            int c = s + dc;
            if (!on(c)) continue;
            if ((board[c] != 0 && Integer.signum(board[c]) != side)) addPawn(s, c, promoRank, out);
            else if (c == epSquare) out.add(new int[]{s, c, 0, 3});   // en passant
        }
    }

    private static void addPawn(int from, int to, int promoRank, List<int[]> out) {
        if (rank(to) == promoRank) {
            out.add(new int[]{from, to, Q, 1});
            out.add(new int[]{from, to, R, 1});
            out.add(new int[]{from, to, B, 1});
            out.add(new int[]{from, to, N, 1});
        } else out.add(new int[]{from, to, 0, 0});
    }

    private static void castleMoves(int s, int side, List<int[]> out) {
        if (side == 1 && s == sq(4, 0)) {
            if (wK && board[sq(5, 0)] == 0 && board[sq(6, 0)] == 0
                    && board[sq(7, 0)] == R
                    && !attacked(sq(4, 0), -1) && !attacked(sq(5, 0), -1) && !attacked(sq(6, 0), -1))
                out.add(new int[]{s, sq(6, 0), 0, 4});
            if (wQ && board[sq(3, 0)] == 0 && board[sq(2, 0)] == 0 && board[sq(1, 0)] == 0
                    && board[sq(0, 0)] == R
                    && !attacked(sq(4, 0), -1) && !attacked(sq(3, 0), -1) && !attacked(sq(2, 0), -1))
                out.add(new int[]{s, sq(2, 0), 0, 5});
        } else if (side == -1 && s == sq(4, 7)) {
            if (bK && board[sq(5, 7)] == 0 && board[sq(6, 7)] == 0
                    && board[sq(7, 7)] == -R
                    && !attacked(sq(4, 7), 1) && !attacked(sq(5, 7), 1) && !attacked(sq(6, 7), 1))
                out.add(new int[]{s, sq(6, 7), 0, 4});
            if (bQ && board[sq(3, 7)] == 0 && board[sq(2, 7)] == 0 && board[sq(1, 7)] == 0
                    && board[sq(0, 7)] == -R
                    && !attacked(sq(4, 7), 1) && !attacked(sq(3, 7), 1) && !attacked(sq(2, 7), 1))
                out.add(new int[]{s, sq(2, 7), 0, 5});
        }
    }

    /** Is square s attacked by the given side (1=white,-1=black)? */
    private static boolean attacked(int s, int bySide) {
        // pawns
        int pd = -16 * bySide;
        for (int dc : new int[]{pd - 1, pd + 1}) {
            int a = s + dc;
            if (on(a) && board[a] == P * bySide) return true;
        }
        for (int d : KN) { int a = s + d; if (on(a) && board[a] == N * bySide) return true; }
        for (int d : KG) { int a = s + d; if (on(a) && board[a] == K * bySide) return true; }
        for (int d : BISHOP) if (slideHit(s, d, bySide, B)) return true;
        for (int d : ROOK) if (slideHit(s, d, bySide, R)) return true;
        return false;
    }

    private static boolean slideHit(int s, int d, int bySide, int pieceType) {
        int a = s + d;
        while (on(a)) {
            int occ = board[a];
            if (occ != 0) {
                int t = Math.abs(occ);
                boolean rightColor = Integer.signum(occ) == bySide;
                if (rightColor && (t == pieceType || t == Q)) return true;
                return false;
            }
            a += d;
        }
        return false;
    }

    private static int kingSquare(boolean white) {
        int target = white ? K : -K;
        for (int s = 0; s < 128; s++) if (on(s) && board[s] == target) return s;
        return -1;
    }

    private static boolean inCheck(boolean white) {
        int ks = kingSquare(white);
        return ks >= 0 && attacked(ks, white ? -1 : 1);
    }

    /** Fully legal moves (pseudo-legal filtered so the mover isn't left in check). */
    private static List<int[]> legalMoves() {
        List<int[]> out = new ArrayList<>();
        boolean side = whiteToMove;
        for (int[] m : pseudoMoves()) {
            Undo u = make(m);
            if (!inCheck(side)) out.add(m);
            unmake(u);
        }
        return out;
    }

    // ── Make / unmake ────────────────────────────────────────────────────────

    private static final class Undo {
        int[] m; int captured; int capSq; int prevEp;
        boolean pwK, pwQ, pbK, pbQ; boolean prevTurn;
    }

    private static Undo make(int[] m) {
        Undo u = new Undo();
        u.m = m; u.prevEp = epSquare; u.pwK = wK; u.pwQ = wQ; u.pbK = bK; u.pbQ = bQ;
        u.prevTurn = whiteToMove;
        int from = m[0], to = m[1], promo = m[2], flag = m[3];
        int pc = board[from];
        int side = Integer.signum(pc);
        u.captured = board[to]; u.capSq = to;

        board[to] = pc; board[from] = 0;
        epSquare = -1;

        if (flag == 2) epSquare = from + 16 * side;                  // double push
        else if (flag == 3) { u.capSq = to - 16 * side; u.captured = board[u.capSq]; board[u.capSq] = 0; } // en passant
        else if (flag == 1) board[to] = promo * side;               // promotion
        else if (flag == 4) { int r = side == 1 ? 0 : 7; board[sq(5, r)] = R * side; board[sq(7, r)] = 0; } // O-O
        else if (flag == 5) { int r = side == 1 ? 0 : 7; board[sq(3, r)] = R * side; board[sq(0, r)] = 0; } // O-O-O

        // castling rights
        if (Math.abs(pc) == K) { if (side == 1) { wK = wQ = false; } else { bK = bQ = false; } }
        if (from == sq(0, 0) || to == sq(0, 0)) wQ = false;
        if (from == sq(7, 0) || to == sq(7, 0)) wK = false;
        if (from == sq(0, 7) || to == sq(0, 7)) bQ = false;
        if (from == sq(7, 7) || to == sq(7, 7)) bK = false;

        whiteToMove = !whiteToMove;
        return u;
    }

    private static void unmake(Undo u) {
        whiteToMove = u.prevTurn;
        epSquare = u.prevEp; wK = u.pwK; wQ = u.pwQ; bK = u.pbK; bQ = u.pbQ;
        int[] m = u.m; int from = m[0], to = m[1], flag = m[3];
        int side = whiteToMove ? 1 : -1;
        int pc = board[to];
        if (flag == 1) pc = P * side;                               // undo promotion
        board[from] = pc; board[to] = 0;
        if (flag == 3) { board[u.capSq] = u.captured; }             // restore ep pawn
        else board[to] = u.captured;
        if (flag == 4) { int r = side == 1 ? 0 : 7; board[sq(7, r)] = R * side; board[sq(5, r)] = 0; }
        if (flag == 5) { int r = side == 1 ? 0 : 7; board[sq(0, r)] = R * side; board[sq(3, r)] = 0; }
    }

    // ── Search (alpha-beta) ──────────────────────────────────────────────────

    private static final int[] VAL = {0, 100, 320, 330, 500, 900, 20000};

    private static int[] bestMove() {
        List<int[]> moves = legalMoves();
        if (moves.isEmpty()) return null;
        orderMoves(moves);
        int side = whiteToMove ? 1 : -1;
        int best = Integer.MIN_VALUE;
        int[] chosen = moves.get(0);
        int alpha = -1_000_000, beta = 1_000_000;
        for (int[] m : moves) {
            Undo u = make(m);
            int score = -alphaBeta(searchDepth - 1, -beta, -alpha, -side);
            unmake(u);
            if (score > best) { best = score; chosen = m; }
            if (score > alpha) alpha = score;
        }
        return chosen;
    }

    private static int alphaBeta(int depth, int alpha, int beta, int side) {
        if (depth == 0) return evaluate() * side;
        List<int[]> moves = legalMoves();
        if (moves.isEmpty()) {
            if (inCheck(whiteToMove)) return -100000 - depth;        // mated
            return 0;                                                // stalemate
        }
        orderMoves(moves);
        int best = -1_000_000;
        for (int[] m : moves) {
            Undo u = make(m);
            int score = -alphaBeta(depth - 1, -beta, -alpha, -side);
            unmake(u);
            if (score > best) best = score;
            if (best > alpha) alpha = best;
            if (alpha >= beta) break;
        }
        return best;
    }

    private static void orderMoves(List<int[]> moves) {
        // Captures first (MVV-LVA-ish) for better pruning.
        moves.sort((a, b) -> capScore(b) - capScore(a));
    }

    private static int capScore(int[] m) {
        int victim = Math.abs(board[m[1]]);
        int attacker = Math.abs(board[m[0]]);
        if (victim == 0) return m[3] == 1 ? 50 : 0;                  // promotion nudge
        return VAL[victim] * 10 - VAL[attacker];
    }

    /** Static evaluation from White's perspective (centipawns). */
    private static int evaluate() {
        int score = 0;
        for (int s = 0; s < 128; s++) {
            if (!on(s)) continue;
            int pc = board[s];
            if (pc == 0) continue;
            int t = Math.abs(pc);
            int v = VAL[t] + pst(t, s, pc > 0);
            score += pc > 0 ? v : -v;
        }
        return score;
    }

    /** Small piece-square nudges: centralize, push pawns, keep king back early. */
    private static int pst(int type, int s, boolean white) {
        int f = file(s), r = white ? rank(s) : 7 - rank(s);
        int center = (3 - Math.abs(2 * f - 7) / 2) + (3 - Math.abs(2 * r - 7) / 2);
        switch (type) {
            case P: return r * 5 + center;
            case N: return center * 6 - 8;
            case B: return center * 4;
            case R: return (r == 6 ? 20 : 0);
            case Q: return center * 2;
            case K: return r <= 1 ? 10 : -15;
            default: return 0;
        }
    }

    // ── Notation ─────────────────────────────────────────────────────────────

    private static int[] parse(String uci) {
        if (uci.length() < 4) return null;
        int ff = uci.charAt(0) - 'a', fr = uci.charAt(1) - '1';
        int tf = uci.charAt(2) - 'a', tr = uci.charAt(3) - '1';
        if (ff < 0 || ff > 7 || tf < 0 || tf > 7 || fr < 0 || fr > 7 || tr < 0 || tr > 7) return null;
        int promo = 0;
        if (uci.length() >= 5) {
            switch (uci.charAt(4)) {
                case 'q': promo = Q; break; case 'r': promo = R; break;
                case 'b': promo = B; break; case 'n': promo = N; break;
            }
        }
        return new int[]{sq(ff, fr), sq(tf, tr), promo, 0};
    }

    private static String moveStr(int[] m) {
        String s = coord(m[0]) + coord(m[1]);
        if (m[2] == Q) s += "q";
        else if (m[2] == R) s += "r";
        else if (m[2] == B) s += "b";
        else if (m[2] == N) s += "n";
        return s;
    }

    private static String coord(int s) {
        return "" + (char) ('a' + file(s)) + (char) ('1' + rank(s));
    }

    private static final String GLYPH = "  PNBRQK";

    private static String ascii() {
        StringBuilder sb = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            sb.append(r + 1).append(' ');
            for (int f = 0; f < 8; f++) {
                int pc = board[sq(f, r)];
                char c = pc == 0 ? '.' : GLYPH.charAt(Math.abs(pc) + 1);
                if (pc < 0) c = Character.toLowerCase(c);
                sb.append(c).append(' ');
            }
            sb.append('\n');
        }
        sb.append("  a b c d e f g h");
        return sb.toString();
    }
}
