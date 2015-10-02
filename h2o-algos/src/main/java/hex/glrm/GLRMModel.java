package hex.glrm;

import hex.*;
import hex.glm.GLM;
import hex.glrm.GLRMInit.*;
import hex.optimization.L_BFGS;
import hex.svd.SVDModel.SVDParameters;
import water.*;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.*;
import water.util.TwoDimTable;

import java.util.Arrays;
import java.util.Random;

public class GLRMModel extends Model<GLRMModel,GLRMModel.GLRMParameters,GLRMModel.GLRMOutput> {
  // Convergence tolerance
  private static final double TOLERANCE = 1e-6;

  // Number of tasks to run in parallel when computing offset term
  private static final int NUM_PARALLEL_TASKS = 10;

  public static class GLRMParameters extends Model.Parameters {
    public DataInfo.TransformType _transform = DataInfo.TransformType.NONE; // Data transformation (demean to compare with PCA)
    public int _k = 1;                            // Rank of resulting XY matrix
    public GLRM.Initialization _init = GLRM.Initialization.PlusPlus;  // Initialization of Y matrix
    public SVDParameters.Method _svd_method = SVDParameters.Method.Randomized;  // SVD initialization method (for _init = SVD)
    public Key<Frame> _user_y;               // User-specified Y matrix (for _init = User)
    public Key<Frame> _user_x;               // User-specified X matrix (for _init = User)

    // Loss functions
    public Loss _loss = Loss.Quadratic;                  // Default loss function for numeric cols
    public Loss _multi_loss = Loss.Categorical;  // Default loss function for categorical cols
    public int _period = 1;                       // Length of the period when _loss = Periodic
    public Loss[] _loss_by_col;                // Override default loss function for specific columns
    public int[] _loss_by_col_idx;
    public boolean _offset = false;  // Include offset term in loss function?
    public boolean _scale = false;   // Scale loss function by generalized column variance?

    // Regularization functions
    public Regularizer _regularization_x = Regularizer.None;   // Regularization function for X matrix
    public Regularizer _regularization_y = Regularizer.None;   // Regularization function for Y matrix
    public double _gamma_x = 0;                   // Regularization weight on X matrix
    public double _gamma_y = 0;                   // Regularization weight on Y matrix
    public double _rho = 1e-5;    // Regularization weight on proximal gradient penalty term

    // Optional parameters
    public int _max_iterations = 1000;            // Max iterations
    public double _init_step_size = 1.0;          // Initial step size (decrease until we hit min_step_size)
    public double _min_step_size = 1e-4;          // Min step size
    public long _seed = System.nanoTime();        // RNG seed
    // public Key<Frame> _loading_key;               // Key to save X matrix
    public String _loading_name;
    public boolean _recover_svd = false;          // Recover singular values and eigenvectors of XY at the end?
    public boolean _verbose = true;               // Log when objective increases each iteration?

    // Quadratic -> Gaussian distribution ~ exp(-(a-u)^2)
    // Absolute -> Laplace distribution ~ exp(-|a-u|)
    public enum Loss {
      Quadratic(true), Absolute(true), Huber(true), Poisson(true), Periodic(true),  // One-dimensional loss (numeric)
      Logistic(true, true), Hinge(true, true),  // Boolean loss (categorical)
      Categorical(false), Ordinal(false);    // Multi-dimensional loss (categorical)

      private boolean forNumeric;
      private boolean forBinary;
      Loss(boolean forNumeric) { this(forNumeric, false); }
      Loss(boolean forNumeric, boolean forBinary) {
        this.forNumeric = forNumeric;
        this.forBinary = forBinary;
      }

      public boolean isForNumeric() { return forNumeric; }
      public boolean isForCategorical() { return !forNumeric; }
      public boolean isForBinary() { return forBinary; }
    }

    // Non-negative matrix factorization (NNMF): r_x = r_y = NonNegative
    // Orthogonal NNMF: r_x = OneSparse, r_y = NonNegative
    // K-means clustering: r_x = UnitOneSparse, r_y = 0 (\gamma_y = 0)
    // Quadratic mixture: r_x = Simplex, r_y = 0 (\gamma_y = 0)
    public enum Regularizer {
      None, Quadratic, L2, L1, NonNegative, OneSparse, UnitOneSparse, Simplex
    }

    // Check if all elements of _loss_by_col are equal to a specific loss function
    private final boolean allLossEquals(Loss loss) {
      if (null == _loss_by_col) return false;

      boolean res = true;
      for(int i = 0; i < _loss_by_col.length; i++) {
        if(_loss_by_col[i] != loss) {
          res = false;
          break;
        }
      }
      return res;
    }

    // Closed form solution only if quadratic loss, no regularization or quadratic regularization (same for X and Y), and no missing values
    public final boolean hasClosedForm() {
      long na_cnt = 0;
      Frame train = _train.get();
      for(int i = 0; i < train.numCols(); i++)
        na_cnt += train.vec(i).naCnt();
      return hasClosedForm(na_cnt);
    }

    public final boolean hasClosedForm(long na_cnt) {
      boolean loss_quad = (null == _loss_by_col && _loss == Loss.Quadratic) ||
              (null != _loss_by_col && allLossEquals(Loss.Quadratic) && (_loss_by_col.length == _train.get().numCols() || _loss == Loss.Quadratic));

      return na_cnt == 0 && ((loss_quad && (_gamma_x == 0 || _regularization_x == Regularizer.None || _regularization_x == GLRMParameters.Regularizer.Quadratic)
              && (_gamma_y == 0 || _regularization_y == Regularizer.None || _regularization_y == GLRMParameters.Regularizer.Quadratic)));
    }

    // L(u,a): Loss function
    public final double loss(double u, double a) {
      return loss(u, a, _loss, _period); }
    public static double loss(double u, double a, Loss loss, int period) {
      assert loss.isForNumeric() : "Loss function " + loss + " not applicable to numerics";
      switch(loss) {
        case Quadratic:
          return (u-a)*(u-a);
        case Absolute:
          return Math.abs(u-a);
        case Huber:
          return Math.abs(u-a) <= 1 ? 0.5*(u-a)*(u-a) : Math.abs(u-a)-0.5;
        case Poisson:
          assert a >= 0 : "Poisson loss L(u,a) requires variable a >= 0";
          return Math.exp(u) + (a == 0 ? 0 : -a*u + a*Math.log(a) - a);   // Since \lim_{a->0} a*log(a) = 0
        case Hinge:
          // return Math.max(1-a*u,0);
          return Math.max(1 - (a == 0 ? -u : u), 0);   // Booleans are coded {0,1} instead of {-1,1}
        case Logistic:
          // return Math.log(1 + Math.exp(-a * u));
          return Math.log(1 + Math.exp(a == 0 ? u : -u));    // Booleans are coded {0,1} instead of {-1,1}
        case Periodic:
          return 1-Math.cos((a-u)*(2*Math.PI)/period);
        default:
          throw new RuntimeException("Unknown loss function " + loss);
      }
    }

    // \grad_u L(u,a): Gradient of loss function with respect to u
    public final double lgrad(double u, double a) { return lgrad(u, a, _loss); }
    public final double lgrad(double u, double a, Loss loss) {
      assert loss.isForNumeric() : "Loss function " + loss + " not applicable to numerics";
      switch(loss) {
        case Quadratic:
          return 2*(u-a);
        case Absolute:
          return Math.signum(u - a);
        case Huber:
          return Math.abs(u-a) <= 1 ? u-a : Math.signum(u-a);
        case Poisson:
          assert a >= 0 : "Poisson loss L(u,a) requires variable a >= 0";
          return Math.exp(u)-a;
        case Hinge:
          // return a*u <= 1 ? -a : 0;
          return a == 0 ? (-u <= 1 ? 1 : 0) : (u <= 1 ? -1 : 0);  // Booleans are coded as {0,1} instead of {-1,1}
        case Logistic:
          // return -a/(1+Math.exp(a*u));
          return a == 0 ? 1/(1+Math.exp(-u)) : -1/(1+Math.exp(u));    // Booleans are coded as {0,1} instead of {-1,1}
        case Periodic:
          return ((2*Math.PI)/_period) * Math.sin((a-u) * (2*Math.PI)/_period);
        default:
          throw new RuntimeException("Unknown loss function " + loss);
      }
    }

    // L(u,a): Multidimensional loss function
    public final double mloss(double[] u, int a) {
      return mloss(u, a, _multi_loss, null); }
    public static double mloss(double[] u, int a, Loss multi_loss) { return mloss(u, a, multi_loss, null); }
    public static double mloss(double[] u, int a, Loss multi_loss, double[] offset) {
      assert multi_loss.isForCategorical() : "Loss function " + multi_loss + " not applicable to categoricals";
      if(a < 0 || a > u.length-1)
        throw new IllegalArgumentException("Index must be between 0 and " + String.valueOf(u.length-1));

      double sum = 0;
      if(null == offset) offset = new double[u.length];
      switch(multi_loss) {
        case Categorical:
          for (int i = 0; i < u.length; i++) sum += Math.max(1 + u[i] + offset[i], 0);
          sum += Math.max(1 - u[a] - offset[a], 0) - Math.max(1 + u[a] + offset[a], 0);
          return sum;
        case Ordinal:
          for (int i = 0; i < u.length-1; i++) sum += Math.max(a>i ? 1-u[i]-offset[i]:1, 0);
          return sum;
        default:
          throw new RuntimeException("Unknown multidimensional loss function " + multi_loss);
      }
    }

    // \grad_u L(u,a): Gradient of multidimensional loss function with respect to u
    public final double[] mlgrad(double[] u, int a) { return mlgrad(u, a, _multi_loss, null); }
    public static double[] mlgrad(double[] u, int a, Loss multi_loss) { return mlgrad(u, a, multi_loss, null); }
    public static double[] mlgrad(double[] u, int a, Loss multi_loss, double[] offset) {
      assert multi_loss.isForCategorical() : "Loss function " + multi_loss + " not applicable to categoricals";
      if(a < 0 || a > u.length-1)
        throw new IllegalArgumentException("Index must be between 0 and " + String.valueOf(u.length-1));

      double[] grad = new double[u.length];
      if(null == offset) offset = new double[u.length];
      switch(multi_loss) {
        case Categorical:
          for (int i = 0; i < u.length; i++) grad[i] = (1+u[i]+offset[i] > 0) ? 1:0;
          grad[a] = (1-u[a]-offset[a] > 0) ? -1:0;
          return grad;
        case Ordinal:
          for (int i = 0; i < u.length-1; i++) grad[i] = (a>i && 1-u[i]-offset[i] > 0) ? -1:0;
          return grad;
        default:
          throw new RuntimeException("Unknown multidimensional loss function " + multi_loss);
      }
    }

    // r_i(x_i), r_j(y_j): Regularization function for single row x_i or column y_j
    public final double regularize_x(double[] u) { return regularize(u, _regularization_x); }
    public final double regularize_y(double[] u) {
      return regularize(u, _regularization_y); }
    public final double regularize(double[] u, Regularizer regularization) {
      if(u == null) return 0;
      double ureg = 0;

      switch(regularization) {
        case None:
          return 0;
        case Quadratic:
          for(int i = 0; i < u.length; i++)
            ureg += u[i] * u[i];
          return ureg;
        case L2:
          for(int i = 0; i < u.length; i++)
            ureg += u[i] * u[i];
          return Math.sqrt(ureg);
        case L1:
          for(int i = 0; i < u.length; i++)
            ureg += Math.abs(u[i]);
          return ureg;
        case NonNegative:
          for(int i = 0; i < u.length; i++) {
            if(u[i] < 0) return Double.POSITIVE_INFINITY;
          }
          return 0;
        case OneSparse:
          int card = 0;
          for(int i = 0; i < u.length; i++) {
            if(u[i] < 0) return Double.POSITIVE_INFINITY;
            else if(u[i] > 0) card++;
          }
          return card == 1 ? 0 : Double.POSITIVE_INFINITY;
        case UnitOneSparse:
          int ones = 0, zeros = 0;
          for(int i = 0; i < u.length; i++) {
            if(u[i] == 1) ones++;
            else if(u[i] == 0) zeros++;
            else return Double.POSITIVE_INFINITY;
          }
          return ones == 1 && zeros == u.length-1 ? 0 : Double.POSITIVE_INFINITY;
        case Simplex:
          double sum = 0;
          for(int i = 0; i < u.length; i++) {
            if(u[i] < 0) return Double.POSITIVE_INFINITY;
            else sum += u[i];
          }
          return MathUtils.equalsWithinOneSmallUlp(sum, 1) ? 0 : Double.POSITIVE_INFINITY;
        default:
          throw new RuntimeException("Unknown regularization function " + regularization);
      }
    }

    // \sum_i r_i(x_i), \sum_j r_j(y_j): Sum of regularization function for all entries of X or Y
    public final double regularize_x(double[][] u) {
      if(_regularization_x == Regularizer.None) return 0;

      double ureg = 0;
      int last = u[0].length;
      for(int i = 0; i < u.length; i++) {
        ureg += regularize(u[i], _regularization_x);
        if(Double.isInfinite(ureg)) return ureg;
      }
      return ureg;
    }
    public final double regularize_y(double[][] u) {
      if(u == null || _regularization_y == Regularizer.None) return 0;

      double ureg = 0;
      for(int i = 0; i < u.length; i++) {
        ureg += regularize(u[i], _regularization_y);
        if(Double.isInfinite(ureg)) return ureg;
      }
      return ureg;
    }

    // \prox_{\alpha_k*r}(u): Proximal gradient of (step size) * (regularization function) evaluated at vector u
    public final double[] rproxgrad_x(double[] u, double alpha, Random rand) { return rproxgrad(u, alpha, _gamma_x, _regularization_x, rand); }
    public final double[] rproxgrad_y(double[] u, double alpha, Random rand) {
      return rproxgrad(u, alpha, _gamma_y, _regularization_y, rand); }
    public final double[] rproxgrad(double[] u, double alpha, double gamma, Regularizer regularization, Random rand) {
      if(u == null || alpha == 0 || gamma == 0) return u;
      double[] v = new double[u.length];

      switch(regularization) {
        case None:
          return u;
        case Quadratic:
          for(int i = 0; i < u.length; i++)
            v[i] = u[i]/(1+2*alpha*gamma);
          return v;
        case L2:
          // Proof uses Moreau decomposition; see section 6.5.1 of Parikh and Boyd https://web.stanford.edu/~boyd/papers/pdf/prox_algs.pdf
          double weight = 1 - alpha*gamma/ArrayUtils.l2norm(u);
          if(weight < 0) return v;   // Zero vector
          for(int i = 0; i < u.length; i++)
            v[i] = weight * u[i];
          return v;
        case L1:
          for(int i = 0; i < u.length; i++)
            v[i] = Math.max(u[i]-alpha*gamma,0) + Math.min(u[i]+alpha*gamma,0);
          return v;
        case NonNegative:
          for(int i = 0; i < u.length; i++)
            v[i] = Math.max(u[i],0);
          return v;
        case OneSparse:
          int idx = ArrayUtils.maxIndex(u, rand);
          v[idx] = u[idx] > 0 ? u[idx] : 1e-6;
          return v;
        case UnitOneSparse:
          idx = ArrayUtils.maxIndex(u, rand);
          v[idx] = 1;
          return v;
        case Simplex:
          // Proximal gradient algorithm by Chen and Ye in http://arxiv.org/pdf/1101.6081v2.pdf
          // 1) Sort input vector u in ascending order: u[1] <= ... <= u[n]
          int n = u.length;
          int[] idxs = new int[n];
          for(int i = 0; i < n; i++) idxs[i] = i;
          ArrayUtils.sort(idxs, u);

          // 2) Calculate cumulative sum of u in descending order
          // cumsum(u) = (..., u[n-2]+u[n-1]+u[n], u[n-1]+u[n], u[n])
          double[] ucsum = new double[n];
          ucsum[n-1] = u[idxs[n-1]];
          for(int i = n-2; i >= 0; i--)
            ucsum[i] = ucsum[i+1] + u[idxs[i]];

          // 3) Let t_i = (\sum_{j=i+1}^n u[j] - 1)/(n - i)
          // For i = n-1,...,1, set optimal t* to first t_i >= u[i]
          double t = (ucsum[0] - 1)/n;    // Default t* = (\sum_{j=1}^n u[j] - 1)/n
          for(int i = n-1; i >= 1; i--) {
            double tmp = (ucsum[i] - 1)/(n - i);
            if(tmp >= u[idxs[i-1]]) {
              t = tmp; break;
            }
          }

          // 4) Return max(u - t*, 0) as projection of u onto simplex
          for(int i = 0; i < n; i++)
            v[i] = Math.max(u[i] - t, 0);
          return v;
        default:
          throw new RuntimeException("Unknown regularization function " + regularization);
      }
    }

    // Project X,Y matrices into appropriate subspace so regularizer is finite. Used during initialization.
    public final double[] project_x(double[] u, Random rand) {
      if(u == null) return u;

      switch(_regularization_x) {
        // Domain is all real numbers
        case None:
        case Quadratic:
        case L2:
        case L1:
          return u;
        // Proximal operator of indicator function for a set C is (Euclidean) projection onto C
        case NonNegative:
        case OneSparse:
        case UnitOneSparse:
          return rproxgrad_x(u, 1, rand);
        case Simplex:
          double reg = regularize(u, _regularization_x);   // Check if inside simplex before projecting since algo is complicated
          if (reg == 0) return u;
          return rproxgrad_x(u, 1, rand);
        default:
          throw new RuntimeException("Unknown regularization function " + _regularization_x);
      }
    }
    public final double[] project_y(double[] u, Random rand) {
      if(u == null) return u;

      switch(_regularization_y) {
        // Domain is all real numbers
        case None:
        case Quadratic:
        case L2:
        case L1:
          return u;
        // Proximal operator of indicator function for a set C is (Euclidean) projection onto C
        case NonNegative:
        case OneSparse:
        case UnitOneSparse:
          return rproxgrad_y(u, 1, rand);
        case Simplex:
          double reg = regularize(u, _regularization_y);   // Check if inside simplex before projecting since algo is complicated
          if (reg == 0) return u;
          return rproxgrad_y(u, 1, rand);
        default:
          throw new RuntimeException("Unknown regularization function " + _regularization_y);
      }
    }

    // \hat A_{i,j} = \argmin_a L_{i,j}(x_iy_j, a): Data imputation for real numeric values
    public final double impute(double u) { return impute(u, _loss); }
    public static double impute(double u, Loss loss) {
      assert loss.isForNumeric() : "Loss function " + loss + " not applicable to numerics";
      switch(loss) {
        case Quadratic:
        case Absolute:
        case Huber:
        case Periodic:
          return u;
        case Poisson:
          return Math.round(Math.exp(u));   // Poisson count requires non-negative integers
        case Hinge:
        case Logistic:
          return u > 0 ? 1 : 0;   // Booleans are coded as {0,1} instead of {-1,1}
        default:
          throw new RuntimeException("Unknown loss function " + loss);
      }
    }

    // \hat A_{i,j} = \argmin_a L_{i,j}(x_iy_j, a): Data imputation for categorical values {0,1,2,...}
    // TODO: Is there a faster way to find the loss minimizer?
    public final int mimpute(double[] u) { return mimpute(u, _multi_loss, null); }
    public static int mimpute(double[] u, Loss multi_loss) { return mimpute(u, multi_loss, null); }
    public static int mimpute(double[] u, Loss multi_loss, double[] offset) {
      assert multi_loss.isForCategorical() : "Loss function " + multi_loss + " not applicable to categoricals";

      if(null == offset) offset = new double[u.length];
      switch(multi_loss) {
        case Categorical:
          return ArrayUtils.maxIndex(u);    // Let u = x_iY_j, then imputed category is \argmax_l u_l
        case Ordinal:
          double[] cand = new double[u.length];
          for (int a = 0; a < cand.length; a++)
            cand[a] = mloss(u, a, multi_loss, offset);
          return ArrayUtils.minIndex(cand);
        default:
          throw new RuntimeException("Unknown multidimensional loss function " + multi_loss);
      }
    }

    public final double offset(Vec v, Loss loss) {
      assert v.isNumeric() : "Vector must be numeric type";
      switch(loss) {
        case Quadratic:
          return v.mean();
        case Absolute:
          return v.median();
        case Huber:
          return v.mean();    // TODO: This isn't quite right - mean is not necessarily minimizer, but close enough
        case Poisson:
          return Math.log(v.mean());
        case Hinge:
          double ones = v.nzCnt();
          double zeros = v.length() - v.naCnt() - ones;
          return ones > zeros ? 1 : 0;
        case Periodic:
          assert _period > 0 : "_period must be a positive integer";
          PeriodOffset tsk = new PeriodOffset(_period).doAll(v);
          return tsk._offset;
        case Logistic:
          LossOffsetSolver gs = new LossOffsetSolver(this, new Frame(v), loss, _period);
          L_BFGS lbfgs = new L_BFGS().setGradEps(1e-8);
          L_BFGS.Result r = lbfgs.solve(gs, new double[] { v.mean() });
          return r.coefs[0];
          // throw H2O.unimpl("Generalized column mean not available for loss function " + loss);
        default:
          throw new RuntimeException("Unknown loss function " + loss);
      }
    }
    public final double[] moffset(Vec v, Loss multi_loss) {
      assert v.isEnum() : "Vector must be enum type";
      switch(multi_loss) {
        case Categorical:
        case Ordinal:
          MultiLossOffsetSolver gs = new MultiLossOffsetSolver(this, new Frame(v), multi_loss);
          L_BFGS lbfgs = new L_BFGS().setGradEps(1e-8);
          double[] coefs = new double[v.cardinality()];
          coefs[Math.min(v.mode(), v.cardinality()-1)] = 1;
          // double[] coefs = L_BFGS.startCoefs(v.cardinality(), _seed);
          L_BFGS.Result r = lbfgs.solve(gs, coefs);
          return r.coefs;
          // throw H2O.unimpl("Generalized column mean not available for multidimensional loss function " + multi_loss);
        default:
          throw new RuntimeException("Unknown multidimensional loss function " + multi_loss);
      }
    }

    public final double scale(Vec v, Loss loss) {
      assert v.isNumeric() : "Vector must be numeric type";
      switch(loss) {
        case Quadratic:   // Quadratic scale is just reciprocal of variance
          double sigma = v.sigma();
          return sigma != 0 ? 1.0 / (sigma * sigma) : 1.0;
        case Absolute:
        case Huber:
        case Poisson:
        case Hinge:
        case Periodic:
        case Logistic:
          throw H2O.unimpl("Generalized column variance not available for loss function " + loss);
        default:
          throw new RuntimeException("Unknown loss function " + loss);
      }
    }
    public final double mscale(Vec v, Loss multi_loss) {
      assert v.isEnum() : "Vector must be enum type";
      switch(multi_loss) {
        case Categorical:
        case Ordinal:
          throw H2O.unimpl("Generalized column variance not available for multidimensional loss function " + multi_loss);
        default:
          throw new RuntimeException("Unknown multidimensional loss function " + multi_loss);
      }
    }

    // Is there a closed form solution for the M-estimator? If not, must use L-BFGS to get minimizer.
    private final boolean closedFormOffset(Loss loss) {
      switch(loss) {
        case Quadratic:
        case Absolute:
        case Huber:
        case Poisson:
        case Hinge:
        case Periodic:
          return true;
        case Logistic:
        case Categorical:
        case Ordinal:
          return false;
        default:
          throw new RuntimeException("Unknown loss function " + loss);
      }
    }

    private final boolean closedFormScale(Loss loss) {
      switch(loss) {
        case Quadratic:
          return true;
        case Absolute:
        case Huber:
        case Poisson:
        case Hinge:
        case Periodic:
        case Logistic:
        case Categorical:
        case Ordinal:
          return false;
        default:
          throw new RuntimeException("Unknown loss function " + loss);
      }
    }

    private final L_BFGS.Result proxSolver(L_BFGS.GradientSolver gs, double[] coefs) {
      return proxSolver(gs, coefs, _rho);
    }
    private final L_BFGS.Result proxSolver(L_BFGS.GradientSolver gs, double[] coefs, double reg) {
      L_BFGS lbfgs = new L_BFGS().setGradEps(1e-8);
      L_BFGS.Result res = null;

      int count = 0;
      double diff = 2 * TOLERANCE;
      double[] coefs_old = coefs.clone();
      double[] rho = new double[coefs.length];
      Arrays.fill(rho, reg);

      while(diff > TOLERANCE && count < _max_iterations) {
        GLM.ProximalGradientSolver ps = new GLM.ProximalGradientSolver(gs, coefs, rho);
        res = lbfgs.solve(ps, coefs);

        diff = ArrayUtils.linfnorm(res.coefs, coefs_old);
        coefs_old = coefs;
        coefs = res.coefs;
        count++;
      }
      if(_verbose) Log.info("Iterations: " + count + "\tDifference: " + diff);
      return res;
    }

    public final void setOffsetScale(DataInfo dinfo, Loss[] losses, GLRMOutput output) {
      Frame fr = dinfo._adaptedFrame;
      long nrows = fr.numRows();
      assert losses != null && losses.length == fr.numCols() : "Number of losses " + losses.length + " != " + fr.numCols();

      output._lossOffset = new double[losses.length][];
      output._lossScale = new double[losses.length];
      if(!_scale) Arrays.fill(output._lossScale, 1.0);
      if(!_offset && !_scale) return;

      double[][] lossOffset = new double[losses.length][];
      double[] lossScale = new double[losses.length];
      boolean[] skipScale = new boolean[losses.length];

      for(int i = 0; i < losses.length; i++) {
        skipScale[i] = closedFormScale(losses[i]) || !closedFormOffset(losses[i]);

        if (closedFormOffset(losses[i])) {
          if(losses[i].isForNumeric())
            lossOffset[i] = new double[] { offset(fr.vec(i), losses[i]) };
          else if(losses[i].isForCategorical())
            lossOffset[i] = moffset(fr.vec(i), losses[i]);

        } else {    // Both offset and scale immediately available from L-BFGS solution
          L_BFGS lbfgs = new L_BFGS().setGradEps(1e-8);
          L_BFGS.Result res = null;

          if(losses[i].isForNumeric()) {
            LossOffsetSolver gs = new LossOffsetSolver(this, new Frame(fr.vec(i)), losses[i], _period);
            res = lbfgs.solve(gs, new double[] { fr.vec(i).mean() });
          } else if(losses[i].isForCategorical()) {
            MultiLossOffsetSolver gs = new MultiLossOffsetSolver(this, new Frame(fr.vec(i)), losses[i]);
            // double[] coefs = new double[fr.vec(i).cardinality()];
            // coefs[Math.min(fr.vec(i).mode(), fr.vec(i).cardinality()-1)] = 1;
            double[] coefs = L_BFGS.startCoefs(fr.vec(i).cardinality(), _seed);
            // res = lbfgs.solve(gs, coefs);
            res = proxSolver(gs, coefs, _rho);
          }

          lossOffset[i] = res.coefs;
          if(_scale) lossScale[i] = res.ginfo._objVal != 0 ? (nrows-fr.vec(i).naCnt()-1) / res.ginfo._objVal : 1.0;
        }

        if(_scale && closedFormScale(losses[i])) {
          if(losses[i].isForNumeric())
            lossScale[i] = scale(fr.vec(i), losses[i]);
          else if(losses[i].isForCategorical())
            lossScale[i] = mscale(fr.vec(i), losses[i]);
        }
      }

      // Additional pass to calculate scaling factor where L-BFGS was not applied and closed form unavailable
      if(_scale && ArrayUtils.sum(skipScale) < losses.length) {
        LossScaleCalc lstsk = new LossScaleCalc(dinfo._cats, losses, _period, lossOffset, lossScale, skipScale);
        lstsk.doAll(dinfo._adaptedFrame);
      }

      if(_offset) output._lossOffset = lossOffset;
      if(_scale) output._lossScale = lossScale;
    }
  }

  public static class GLRMOutput extends Model.Output {
    // Iterations executed
    public int _iterations;

    // Current value of objective function
    public double _objective;

    // Average change in objective function this iteration
    public double _avg_change_obj;
    public double[/*iterations*/] _history_avg_change_obj = new double[0];

    // Mapping from lower dimensional k-space to training features (Y)
    public TwoDimTable _archetypes;
    public GLRM.Archetypes _archetypes_raw;   // Needed for indexing into Y for scoring

    // Step size each iteration
    public double[/*iterations*/] _history_step_size = new double[0];

    // SVD of output XY
    public double[/*feature*/][/*k*/] _eigenvectors_raw;
    public TwoDimTable _eigenvectors;
    public double[] _singular_vals;

    // Frame key of X matrix
    public Key<Frame> _loading_key;
    public int _ncolX;    // Number of columns in X (k)

    // Number of categorical and numeric columns
    public int _ncats;
    public int _nnums;

    // Number of good rows in training frame (not skipped)
    public long _nobs;

    // Categorical offset vector
    public int[] _catOffsets;

    // Standardization arrays for numeric data columns
    public double[] _normSub;   // Mean of each numeric column
    public double[] _normMul;   // One over standard deviation of each numeric column

    // Permutation array mapping adapted to original training col indices
    public int[] _permutation;  // _permutation[i] = j means col i in _adaptedFrame maps to col j of _train

    // Expanded column names of adapted training frame
    public String[] _names_expanded;

    // Loss function for every column in adapted training frame
    public GLRMParameters.Loss[] _lossFunc;
    public double[][] _lossOffset;  // Generalized mean of each column (multi-dimensional for categoricals)
    public double[] _lossScale;   // One over generalized variance of each column

    // Training time
    public long[/*iterations*/] _training_time_ms = new long[0];

    public GLRMOutput(GLRM b) { super(b); }

    /** Override because base class implements ncols-1 for features with the
     *  last column as a response variable; for GLRM all the columns are features. */
    @Override public int nfeatures() { return _names.length; }

    @Override public ModelCategory getModelCategory() {
      return ModelCategory.DimReduction;
    }
  }

  public GLRMModel(Key selfKey, GLRMParameters parms, GLRMOutput output) { super(selfKey, parms, output); }

  // GLRM scoring is data imputation based on feature domains using reconstructed XY (see Udell (2015), Section 5.3)
  @Override protected Frame predictScoreImpl(Frame orig, Frame adaptedFr, String destination_key) {
    final int ncols = _output._names.length;
    assert ncols == adaptedFr.numCols();
    String prefix = "reconstr_";

    // Need [A,X,P] where A = adaptedFr, X = loading frame, P = imputed frame
    // Note: A is adapted to original training frame, P has columns shuffled so cats come before nums!
    Frame fullFrm = new Frame(adaptedFr);
    Frame loadingFrm = DKV.get(_output._loading_key).get();
    fullFrm.add(loadingFrm);
    String[][] adaptedDomme = adaptedFr.domains();
    for(int i = 0; i < ncols; i++) {
      Vec v = fullFrm.anyVec().makeZero();
      v.setDomain(adaptedDomme[i]);
      fullFrm.add(prefix + _output._names[i], v);
    }
    GLRMScore gs = new GLRMScore(ncols, loadingFrm.numCols(), true).doAll(fullFrm);

    // Return the imputed training frame
    int x = ncols + _parms._k, y = fullFrm.numCols();
    Frame f = fullFrm.extractFrame(x, y);  // this will call vec_impl() and we cannot call the delete() below just yet

    f = new Frame((null == destination_key ? Key.make() : Key.make(destination_key)), f.names(), f.vecs());
    DKV.put(f);
    gs._mb.makeModelMetrics(GLRMModel.this, adaptedFr);   // save error metrics based on imputed data
    return f;
  }

  private class GLRMScore extends MRTask<GLRMScore> {
    final int _ncolA;   // Number of cols in original data A
    final int _ncolX;   // Number of cols in X (rank k)
    final boolean _save_imputed;  // Save imputed data into new vecs?
    ModelMetrics.MetricBuilder _mb;

    GLRMScore( int ncolA, int ncolX, boolean save_imputed ) {
      _ncolA = ncolA; _ncolX = ncolX; _save_imputed = save_imputed;
    }

    @Override public void map( Chunk chks[] ) {
      float atmp [] = new float[_ncolA];
      double xtmp [] = new double[_ncolX];
      double preds[] = new double[_ncolA];
      _mb = GLRMModel.this.makeMetricBuilder(null);

      if (_save_imputed) {
        for (int row = 0; row < chks[0]._len; row++) {
          double p[] = impute_data(chks, row, xtmp, preds);
          compute_metrics(chks, row, atmp, p);
          for (int c = 0; c < preds.length; c++)
            chks[_ncolA + _ncolX + c].set(row, p[c]);
        }
      } else {
        for (int row = 0; row < chks[0]._len; row++) {
          double p[] = impute_data(chks, row, xtmp, preds);
          compute_metrics(chks, row, atmp, p);
        }
      }
    }

    @Override public void reduce( GLRMScore other ) { if(_mb != null) _mb.reduce(other._mb); }

    @Override protected void postGlobal() { if(_mb != null) _mb.postGlobal(); }

    private float[] compute_metrics(Chunk[] chks, int row_in_chunk, float[] tmp, double[] preds) {
      for( int i=0; i<tmp.length; i++)
        tmp[i] = (float)chks[i].atd(row_in_chunk);
      _mb.perRow(preds, tmp, GLRMModel.this);
      return tmp;
    }

    private double[] impute_data(Chunk[] chks, int row_in_chunk, double[] tmp, double[] preds) {
      for( int i=0; i<tmp.length; i++ )
        tmp[i] = chks[_ncolA+i].atd(row_in_chunk);
      impute_data(tmp, preds);
      return preds;
    }

    // TODO: Add parameter to select imputation including/excluding offset (include = impute original train, exclude = impute de-meaned train)
    private double[] impute_data(double[] tmp, double[] preds) {
      assert preds.length == _output._nnums + _output._ncats;

      // Categorical columns
      for (int d = 0; d < _output._ncats; d++) {
        double[] xyblock = _output._archetypes_raw.lmulCatBlock(tmp,d);
        preds[_output._permutation[d]] = _parms.mimpute(xyblock, _output._lossFunc[d], _output._lossOffset[d]);
      }

      // Numeric columns
      for (int d = _output._ncats; d < preds.length; d++) {
        int ds = d - _output._ncats;
        double xy = _output._archetypes_raw.lmulNumCol(tmp, ds);
        if(_parms._offset) xy += _output._lossOffset[d][0];
        preds[_output._permutation[d]] = _parms.impute(xy, _output._lossFunc[d]);
      }
      return preds;
    }
  }

  @Override protected double[] score0(double[] data, double[] preds) {
    throw H2O.unimpl();
  }

  public ModelMetricsGLRM scoreMetricsOnly(Frame frame) {
    final int ncols = _output._names.length;

    // Need [A,X] where A = adapted test frame, X = loading frame
    // Note: A is adapted to original training frame
    Frame adaptedFr = new Frame(frame);
    adaptTestForTrain(adaptedFr, true, false);
    assert ncols == adaptedFr.numCols();

    // Append loading frame X for calculating XY
    Frame fullFrm = new Frame(adaptedFr);
    Frame loadingFrm = DKV.get(_output._loading_key).get();
    fullFrm.add(loadingFrm);

    GLRMScore gs = new GLRMScore(ncols, loadingFrm.numCols(), false).doAll(fullFrm);
    ModelMetrics mm = gs._mb.makeModelMetrics(GLRMModel.this, adaptedFr);   // save error metrics based on imputed data
    return (ModelMetricsGLRM) mm;
  }

  @Override public ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain) {
    return new ModelMetricsGLRM.GLRMModelMetrics(_output._ncolX, _output._permutation);
  }
}
