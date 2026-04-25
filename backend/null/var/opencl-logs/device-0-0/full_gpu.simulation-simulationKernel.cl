#pragma OPENCL EXTENSION cl_khr_fp64 : enable  
#pragma OPENCL EXTENSION cl_khr_fp16 : enable  
#pragma OPENCL EXTENSION cl_khr_int64_base_atomics : enable  
__kernel void simulationKernel(__global long *_kernel_context, __constant uchar *_constant_region, __local uchar *_local_region, __global int *_atomics, __global uchar *prices, __global uchar *heuristicScores, __global uchar *maGaps, __global uchar *subset, __global uchar *params, __global uchar *gridData, __global uchar *results, __private int popSize, __private int days)
{
  int i_49, i_47, i_46, i_53, i_52, i_51, i_50, i_57, i_54, i_61, i_60, i_59, i_58, i_65, i_64, i_69, i_68, i_67, i_66, i_73, i_72, i_77, i_76, i_75, i_74, i_85, i_89, i_88, i_93, i_97, i_96, i_95, i_94, i_101, i_100, i_104, i_103, i_107, i_106, i_110, i_7, i_13, i_12, i_11, i_17, i_15, i_14, i_21, i_20, i_19, i_18, i_23, i_22, i_27, i_26, i_33, i_32, i_31, i_30, i_37, i_35, i_34, i_41, i_40, i_39, i_38, i_45; 
  ulong ul_24, ul_55, ul_16, ul_108, ul_78, ul_9, ul_105, ul_8, ul_10, ul_5, ul_4, ul_6, ul_70, ul_102, ul_1, ul_0, ul_3, ul_2, ul_98, ul_28, ul_62; 
  double d_63, d_29, d_91, d_90, d_25, d_56, d_84, d_83, d_82, d_81, d_80, d_79, d_109, d_43, d_42, d_71, d_99; 
  bool b_36, b_48; 
  long l_44, l_92, l_87, l_86; 

  // BLOCK 0
  ul_0  =  (ulong) prices;
  ul_1  =  (ulong) heuristicScores;
  ul_2  =  (ulong) maGaps;
  ul_3  =  (ulong) subset;
  ul_4  =  (ulong) params;
  ul_5  =  (ulong) gridData;
  ul_6  =  (ulong) results;
  i_7  =  get_global_size(0);
  ul_8  =  ul_5 + 24;
  ul_9  =  ul_5 + 20;
  ul_10  =  ul_5 + 16;
  i_11  =  get_global_id(0);
  // BLOCK 1 MERGES [0 19 ]
  i_12  =  i_11;
  for(;i_12 < 5;)
  {
    // BLOCK 2
    i_13  =  i_12 % 5;
    i_14  =  i_13 + 4;
    i_15  =  i_14 << 2;
    ul_16  =  ul_3 + i_15;
    i_17  =  *((__global int *) ul_16);
    i_18  =  i_12 / 5;
    i_19  =  i_18 << 4;
    i_20  =  i_18 << 3;
    i_21  =  i_19 + i_20;
    i_22  =  i_21 + 18;
    i_23  =  i_22 << 3;
    ul_24  =  ul_4 + i_23;
    d_25  =  *((__global double *) ul_24);
    i_26  =  i_21 + 2;
    i_27  =  i_26 << 3;
    ul_28  =  ul_4 + i_27;
    d_29  =  *((__global double *) ul_28);
    i_30  =  *((__global int *) ul_10);
    i_31  =  *((__global int *) ul_9);
    i_32  =  *((__global int *) ul_8);
    i_33  =  200 - i_30;
    i_34  =  (i_33 < 1) ? 0 : i_33;
    i_35  =  i_34 + i_31;
    b_36  =  i_32 < 1;
    if(b_36)
    {
      // BLOCK 3
      i_37  =  200;
    }  // B3
    else
    {
      // BLOCK 4
      i_38  =  i_35 + i_32;
      i_39  =  (i_38 < 200) ? i_38 : 200;
      i_37  =  i_39;
    }  // B4
    // BLOCK 5 MERGES [4 3 ]
    i_40  =  (i_35 < 200) ? i_35 : 200;
    // BLOCK 6 MERGES [5 18 ]
    i_41  =  0;
    d_42  =  0.0;
    d_43  =  0.0;
    l_44  =  0L;
    i_45  =  -1;
    i_46  =  i_34;
    for(;i_46 < i_40;)
    {
      // BLOCK 7
      i_47  =  i_46 + 1;
      b_48  =  i_45 < i_46;
      if(b_48)
      {
        // BLOCK 8
        i_49  =  i_13 * 200;
        i_50  =  i_18 * 1000;
        i_51  =  i_49 + i_50;
        i_52  =  i_51 + 2;
        i_53  =  i_52 + i_46;
        i_54  =  i_53 << 3;
        ul_55  =  ul_1 + i_54;
        d_56  =  *((__global double *) ul_55);
        i_57  =  isless(d_25, d_56);
        if(i_57 == 1)
        {
          // BLOCK 9
          i_58  =  i_17 * 200;
          i_59  =  i_58 + 2;
          i_60  =  i_59 + i_46;
          i_61  =  i_60 << 3;
          ul_62  =  ul_0 + i_61;
          d_63  =  *((__global double *) ul_62);
          i_64  =  i_37 + -1;
          i_65  =  i_49 + 2;
          // BLOCK 10 MERGES [9 14 ]
          i_66  =  i_64;
          i_67  =  i_47;
          for(;i_67 < i_37;)
          {
            // BLOCK 11
            i_68  =  i_65 + i_67;
            i_69  =  i_68 << 3;
            ul_70  =  ul_2 + i_69;
            d_71  =  *((__global double *) ul_70);
            i_72  =  isless(d_71, d_29);
            if(i_72 == 1)
            {
              // BLOCK 12
              i_73  =  i_67;
              i_74  =  i_37;
            }  // B12
            else
            {
              // BLOCK 13
              i_73  =  i_66;
              i_74  =  i_67;
            }  // B13
            // BLOCK 14 MERGES [13 12 ]
            i_75  =  i_74 + 1;
            i_66  =  i_73;
            i_67  =  i_75;
          }  // B14
          // BLOCK 15
          i_76  =  i_66 + i_59;
          i_77  =  i_76 << 3;
          ul_78  =  ul_0 + i_77;
          d_79  =  *((__global double *) ul_78);
          d_80  =  d_79 - d_63;
          d_81  =  d_80 / d_63;
          d_82  =  d_81 * 100.0;
          d_83  =  fma(d_82, d_82, d_43);
          d_84  =  fma(d_81, 100.0, d_42);
          i_85  =  i_66 - i_46;
          l_86  =  (long) i_85;
          l_87  =  l_44 + l_86;
          i_88  =  i_41 + 1;
          i_89  =  i_88;
          d_90  =  d_84;
          d_91  =  d_83;
          l_92  =  l_87;
          i_93  =  i_66;
        }  // B9
        else
        {
          // BLOCK 16
          i_89  =  i_41;
          d_90  =  d_42;
          d_91  =  d_43;
          l_92  =  l_44;
          i_93  =  i_45;
        }  // B16
      }  // B8
      else
      {
        // BLOCK 17
        i_89  =  i_41;
        d_90  =  d_42;
        d_91  =  d_43;
        l_92  =  l_44;
        i_93  =  i_45;
        // BLOCK 18 MERGES [17 15 16 ]
        i_94  =  i_47;
        i_41  =  i_89;
        d_42  =  d_90;
        d_43  =  d_91;
        l_44  =  l_92;
        i_45  =  i_93;
        i_46  =  i_94;
      }  // B18
      // BLOCK 19
      i_95  =  i_12 << 2;
      i_96  =  i_95 + 2;
      i_97  =  i_96 << 3;
      ul_98  =  ul_6 + i_97;
      d_99  =  (double) i_41;
      *((__global double *) ul_98)  =  d_99;
      i_100  =  i_95 + 3;
      i_101  =  i_100 << 3;
      ul_102  =  ul_6 + i_101;
      *((__global double *) ul_102)  =  d_42;
      i_103  =  i_95 + 4;
      i_104  =  i_103 << 3;
      ul_105  =  ul_6 + i_104;
      *((__global double *) ul_105)  =  d_43;
      i_106  =  i_95 + 5;
      i_107  =  i_106 << 3;
      ul_108  =  ul_6 + i_107;
      d_109  =  (double) l_44;
      *((__global double *) ul_108)  =  d_109;
      i_110  =  i_7 + i_12;
      i_12  =  i_110;
      // BLOCK 20
      return;
    }  // B20
  }  //  kernel
