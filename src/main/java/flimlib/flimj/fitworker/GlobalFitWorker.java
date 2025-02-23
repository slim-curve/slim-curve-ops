/*-
 * #%L
 * Fluorescence lifetime analysis in ImageJ.
 * %%
 * Copyright (C) 2017 - 2022 Board of Regents of the University of Wisconsin-Madison.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package flimlib.flimj.fitworker;

import java.util.List;

import net.imagej.ops.OpEnvironment;
import flimlib.flimj.FitParams;
import flimlib.flimj.FitResults;
import flimlib.flimj.utils.RAHelper;
import net.imglib2.type.numeric.RealType;
import flimlib.FitType;
import flimlib.Float2DMatrix;
import flimlib.FLIMLib;

public class GlobalFitWorker<I extends RealType<I>> extends AbstractFitWorker<I> {

	public GlobalFitWorker(FitParams<I> params, FitResults results, OpEnvironment ops) {
		super(params, results, ops);
	}

	@Override
	public void fitBatch(List<int[]> pos, FitEventHandler<I> handler) {
		int nTrans = pos.size();

		// trans data and fitted parameters for each trans
		final float[][] trans = new float[nTrans][nDataTotal];
		final float[][] param = new float[nTrans][nParam];
		final boolean[] transSkipped = new boolean[nTrans];

		final RAHelper<I> helper = new RAHelper<>(params, results);

		// fetch parameters from RA
		for (int i = 0; i < nTrans; i++)
			transSkipped[i] = !helper.loadData(trans[i], param[i], params, pos.get(i));

		// each row is a transient series
		Float2DMatrix transMat = new Float2DMatrix(trans);
		// each row is a parameter series
		Float2DMatrix paramMat = new Float2DMatrix(param);
		// only the first row is used
		Float2DMatrix fittedMat = new Float2DMatrix(1, nDataTotal);
		Float2DMatrix residualMat = new Float2DMatrix(1, nDataTotal);
		// $\chi^2$ for each trans
		float[] chisq = new float[nTrans];
		// global $\chi^2$
		float[] chisqGlobal = new float[1];
		// degrees of freedom (used to reduce $\chi^2$)
		int[] df = new int[1];

		final int retCode = FLIMLib.GCI_marquardt_global_exps_instr(params.xInc, transMat,
				adjFitStart, adjFitEnd, params.instr, params.noise, params.sig,
				FitType.FIT_GLOBAL_MULTIEXP, paramMat, params.paramFree, params.restrain,
				params.chisq_delta, fittedMat, residualMat, chisq, chisqGlobal, df,
				params.dropBad ? 1 : 0);
	
		// fetch fitted stuff from native
		float[][] fittedParam  = params.getParamMap     ? paramMat.asArray()    : null;
		float[][] fitted       = params.getFittedMap    ? fittedMat.asArray()   : null;
		float[][] residual     = params.getResidualsMap ? residualMat.asArray() : null;

		// copy back
		for (int i = 0; i < nTrans; i++) {
			results.param     = params.getParamMap     ? fittedParam[i] : null;
			results.fitted    = params.getFittedMap    ? fitted[i]      : null;
			results.residuals = params.getResidualsMap ? residual[i]    : null;
			results.chisq = chisq[i];
			results.retCode = transSkipped[i] ? FitResults.RET_INTENSITY_BELOW_THRESH
					: convertRetCode(retCode);

			if (params.dropBad && results.retCode == FitResults.RET_OK) {
				// GCI_marquardt_global_exps_calculate_exps_instr fills chisq with -1 if
				// drop_bad_transients is true and GCI_marquardt_global_exps_do_fit_single fails
				if (results.chisq < 0)
					results.retCode = FitResults.RET_BAD_FIT_DIVERGED;
				else if (Float.isNaN(results.chisq) || results.chisq > 1E5)
					results.retCode = FitResults.RET_BAD_FIT_CHISQ_OUT_OF_RANGE;
			}

			helper.commitRslts(params, results, pos.get(i));
		}
		results.chisq = chisqGlobal[0];

		if (handler != null)
			handler.onComplete(params, results);
	}

	/**
	 * Roughly categorize return code from {@link FLIMLib#GCI_marquardt_global_exps_instr}.
	 * "Roughly" in the sense that some of the recutrn code are hard to trace while others overlap.
	 * 
	 * @param retCode return code from <code>GCI_marquardt_global_exps_instr</code>
	 * @return return code defined in {@link #FitResults}
	 */
	private int convertRetCode(final int retCode) {
		int convertedretCode;
		switch (retCode) {
			case -1: // bad parameter
			case -12: // bad fit type
			case -21: // bad fit type
			case -22: // bad fit type in GCI_marquardt_global_exps_calculate_exps_instr
			case -31: // bad fit type in GCI_marquardt_global_exps_do_fit_instr
			case -32: // bad fit type in GCI_marquardt_global_exps_do_fit_instr
				convertedretCode = FitResults.RET_BAD_SETTING;
				break;

			case -2: // malloc failed
			case -3: // malloc failed
			case -4: // malloc failed
			case -5: // malloc failed
			case -11: // calloc failed
				convertedretCode = FitResults.RET_INTERNAL_ERROR;
				break;

			case -13: // initial MLA failed
				convertedretCode = FitResults.RET_BAD_FIT_DIVERGED;
				break;

			default: // non-negative: iteration count
				convertedretCode = retCode >= 0 ? FitResults.RET_OK : FitResults.RET_UNKNOWN;
				break;
		}
		return convertedretCode;
	}
}
