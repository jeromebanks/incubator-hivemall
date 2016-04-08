/*
 * Hivemall: Hive scalable Machine Learning Library
 *
 * Copyright (C) 2015 Makoto YUI
 * Copyright (C) 2013-2015 National Institute of Advanced Industrial Science and Technology (AIST)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package hivemall.fm;

import hivemall.common.EtaEstimator;
import hivemall.utils.lang.NumberUtils;

import java.util.Arrays;
import java.util.List;

import javax.annotation.Nonnull;

public abstract class FieldAwareFactorizationMachineModel extends FactorizationMachineModel {

    private boolean useAdaGrad;
    public double eta_debug;

    public FieldAwareFactorizationMachineModel(boolean classification, int factor, float lambda0,
            double sigma, long seed, double minTarget, double maxTarget, EtaEstimator eta,
            VInitScheme vInit, boolean useAdaGrad) {
        super(classification, factor, lambda0, sigma, seed, minTarget, maxTarget, eta, vInit);
        this.useAdaGrad = useAdaGrad;
    }

    public abstract float getV(@Nonnull Feature x, @Nonnull String field, int f);

    protected abstract void setV(@Nonnull Feature x, @Nonnull String yField, int f, float nextVif);

    @Override
    public float getV(Feature x, int f) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void setV(Feature x, int f, float nextVif) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected double predict(Feature[] x) {
        // w0
        double ret = getW0();
        // W
        for (Feature e : x) {
            double xj = e.getValue();
            float w = getW(e);
            double wx = w * xj;
            ret += wx;
        }
        // V
        for (int f = 0, k = _factor; f < k; f++) {
            for (int i = 0; i < x.length; ++i) {
                for (int j = i + 1; j < x.length; ++j) {
                    Feature ei = x[i];
                    Feature ej = x[j];
                    double xi = ei.getValue();
                    double xj = ej.getValue();
                    float vijf = getV(ei, ej.getField(), f);
                    float vjif = getV(ej, ei.getField(), f);
                    ret += vijf * vjif * xi * xj;
                    assert (!Double.isNaN(ret));
                }
            }
        }
        if (!NumberUtils.isFinite(ret)) {
            throw new IllegalStateException(
                "Detected " + ret + " in predict. We recommend to normalize training examples.\n"
                        + "Dumping variables ...\n" + super.varDump(x));
        }
        return ret;
    }

    void updateV(final double dloss, @Nonnull final Feature x, final int f, final double sumViX,
            float eta, final double eps, final double scaling, String field) {
        final double Xi = x.getValue();
        float currentV = getV(x, field, f);
        double h = Xi * sumViX;
        float gradV = (float) (dloss * h);
        float LambdaVf = getLambdaV(f);
        if (useAdaGrad) {
            Entry current = getEntry(x, field);
            current.addGradient(gradV, scaling);
            double adagrad = current.getSumOfSquaredGradients();
            eta /= Math.sqrt(eps + adagrad);
            eta_debug = eta;
        }
        float nextV = currentV - eta * (gradV + 2.f * LambdaVf * currentV);
        if (!NumberUtils.isFinite(nextV)) {
            throw new IllegalStateException(
                "Got " + nextV + " for next V" + f + '[' + x.getFeature() + "]\n" + "Xi=" + Xi
                        + ", Vif=" + currentV + ", h=" + h + ", gradV=" + gradV + ", lambdaVf="
                        + LambdaVf + ", dloss=" + dloss + ", sumViX=" + sumViX);
        }
        setV(x, field, f, nextV);
    }

    /**
     * sum{XiViaf} where a is field index of Xi
     */
    double[][][] sumVfX(@Nonnull Feature[] x, @Nonnull List<String> fieldList) {
        final int factors = _factor;
        final int fieldSize = fieldList.size();
        final int xSize = x.length;
        final double[][][] ret = new double[xSize][fieldSize][factors];
        for (int i = 0; i < xSize; ++i) {
            for (int fieldIndex = 0; fieldIndex < fieldSize; ++fieldIndex) {
                for (int f = 0; f < factors; f++) {
                    ret[i][fieldIndex][f] = sumVfX(x, i, fieldList.get(fieldIndex), f);
                }
            }
        }
        return ret;
    }

    private double sumVfX(@Nonnull final Feature[] x, final int i, @Nonnull final String field,
            final int f) {
        double ret = 0.d;
        // find all other features whose field matches field
        for (Feature e : x) {
            if (x[i].getFeature().equals(e.getFeature())) { // ignore x[i] = e
                continue;
            }
            if (e.getField().equals(field)) { // multiply x_e and v_d,field(e),f
                double xj = x[i].getValue();
                float Vjf = getV(e, x[i].getField(), f);
                ret += Vjf * xj;
            }
        }
        if (!NumberUtils.isFinite(ret)) {
            throw new IllegalStateException("Got " + ret + " for sumV[ " + i + "][ " + f + "]X.\n"
                    + "x = " + Arrays.toString(x));
        }
        return ret;
    }

    protected abstract Entry getEntry(@Nonnull Feature x, @Nonnull String yField);//TODO yField should be Object, not String (for IntFeature support)

    protected Entry newEntry(float[] V) {
        if (useAdaGrad) {
            return new AdaGradEntry(0.f, V);
        } else {
            return new Entry(0.f, V);
        }
    }
    
    static class Entry {
        float W;
        @Nonnull
        final float[] Vf;

        Entry(float W, @Nonnull float[] Vf) {
            this.W = W;
            this.Vf = Vf;
        }

        public double getSumOfSquaredGradients() {
            throw new UnsupportedOperationException();
        }

        public void addGradient(double grad, double scaling) {
            throw new UnsupportedOperationException();
        }
    }

    static class AdaGradEntry extends Entry {
        double sumOfSqGradients;

        AdaGradEntry(float W, float[] Vf) {
            super(W, Vf);
            sumOfSqGradients = 0.d;
        }

        @Override
        public double getSumOfSquaredGradients() {
            return sumOfSqGradients;
        }

        @Override
        public void addGradient(double grad, double scaling) {
            sumOfSqGradients += grad * grad/scaling;
        }

    }
}
