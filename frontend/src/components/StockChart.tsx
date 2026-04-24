import React, { useEffect, useRef, useState } from 'react';
import { createChart, ColorType, CandlestickSeries, LineSeries, HistogramSeries, createSeriesMarkers } from 'lightweight-charts';
import type { IChartApi } from 'lightweight-charts';
import { Maximize2, Minimize2, Pencil, MousePointer2 } from 'lucide-react';

interface StockChartProps {
    data: any; // StockGraphState
    markers?: any[]; // TradePoint[]
    comparisonData?: any[]; // StockGraphState[]
}

const StockChart: React.FC<StockChartProps> = ({ data, markers, comparisonData = [] }) => {
    const chartContainerRef = useRef<HTMLDivElement>(null);
    const tooltipRef = useRef<HTMLDivElement>(null);
    const chartRef = useRef<IChartApi | null>(null);
    const [isFullscreen, setIsFullscreen] = useState(false);
    const [tool, setTool] = useState<'pan' | 'line'>('pan');

    useEffect(() => {
        if (!chartContainerRef.current) return;

        const isComparison = comparisonData.length > 0;

        const chart = createChart(chartContainerRef.current, {
            layout: {
                background: { type: ColorType.Solid, color: '#1e293b' },
                textColor: '#94a3b8',
            },
            grid: {
                vertLines: { color: '#334155' },
                horzLines: { color: '#334155' },
            },
            localization: {
                priceFormatter: (price: number) => isComparison ? `${price.toFixed(2)}%` : `$${price.toFixed(2)}`,
            },
            width: chartContainerRef.current.clientWidth,
            height: isFullscreen ? window.innerHeight - 100 : 400,
        });

        const mainSeries = isComparison 
            ? chart.addSeries(LineSeries, { color: '#10b981', lineWidth: 3, title: data.stock.ticker_symbol })
            : chart.addSeries(CandlestickSeries, {
                upColor: '#10b981',
                downColor: '#ef4444',
                borderVisible: false,
                wickUpColor: '#10b981',
                wickDownColor: '#ef4444',
            });

        const volumeSeries = !isComparison ? chart.addSeries(HistogramSeries, {
            color: '#3b82f6',
            priceFormat: { type: 'volume' },
            priceScaleId: '', // overlay
        }) : null;

        if (volumeSeries) {
            volumeSeries.priceScale().applyOptions({
                scaleMargins: { top: 0.8, bottom: 0 },
            });
        }

        const maSeries = !isComparison ? chart.addSeries(LineSeries, {
            color: '#3b82f6',
            lineWidth: 2,
            title: 'Long MA',
        }) : null;

        // Prepare data
        if (!data?.closePrices || !data?.dates) {
            console.error('Incomplete data for chart', data);
            return;
        }

        const firstPrice = data.closePrices[0];
        const chartData: any[] = [];
        const volData: any[] = [];

        data.closePrices.forEach((price: number, i: number) => {
            if (data.dates[i] === undefined) return;
            const time = data.dates[i];
            
            if (isComparison) {
                chartData.push({
                    time,
                    value: ((price - firstPrice) / firstPrice) * 100,
                });
            } else {
                const open = i > 0 ? data.closePrices[i-1] : price;
                chartData.push({
                    time,
                    open: open,
                    high: Math.max(open, price) * 1.005,
                    low: Math.min(open, price) * 0.995,
                    close: price,
                });
                
                if (data.volumes?.[i]) {
                    volData.push({
                        time,
                        value: data.volumes[i],
                        color: price >= open ? 'rgba(16, 185, 129, 0.5)' : 'rgba(239, 68, 68, 0.5)',
                    });
                }
            }
        });

        if (maSeries && data.avgs) {
            const maData = data.avgs.map((avg: number, i: number) => {
                if (data.dates[i] === undefined || avg === null || avg === undefined) return null;
                return { time: data.dates[i], value: avg };
            }).filter(Boolean);
            maSeries.setData(maData as any);
        }

        mainSeries.setData(chartData as any);
        if (volumeSeries && volData.length) volumeSeries.setData(volData as any);

        // Comparison Series
        if (isComparison) {
            const colors = ['#3b82f6', '#f59e0b', '#8b5cf6', '#ec4899', '#06b6d4'];
            comparisonData.forEach((comp, idx) => {
                const compSeries = chart.addSeries(LineSeries, {
                    color: colors[idx % colors.length],
                    lineWidth: 2,
                    title: comp.stock.ticker_symbol,
                });
                const compFirstPrice = comp.closePrices[0];
                const compData = comp.closePrices.map((p: number, i: number) => ({
                    time: comp.dates[i],
                    value: ((p - compFirstPrice) / compFirstPrice) * 100,
                })).filter((d: any) => d.time);
                compSeries.setData(compData as any);
            });
        }

        // Tooltip logic
        chart.subscribeCrosshairMove(param => {
            if (!tooltipRef.current || !chartContainerRef.current) return;
            
            if (param.point === undefined || !param.time || param.point.x < 0 || param.point.x > chartContainerRef.current.clientWidth || param.point.y < 0 || param.point.y > chartContainerRef.current.clientHeight) {
                tooltipRef.current.style.display = 'none';
            } else {
                tooltipRef.current.style.display = 'block';
                const dataPoint = param.seriesData.get(mainSeries);
                const volumePoint = volumeSeries ? param.seriesData.get(volumeSeries) : null;
                
                if (dataPoint) {
                    const price = (dataPoint as any).value !== undefined ? (dataPoint as any).value : (dataPoint as any).close;
                    const vol = (volumePoint as any)?.value;
                    
                    tooltipRef.current.innerHTML = `
                        <div class="flex flex-col gap-1">
                            <div class="text-slate-400 font-bold text-[10px] uppercase tracking-widest">${param.time}</div>
                            <div class="flex items-center justify-between gap-4">
                                <span class="text-white text-lg font-black">${isComparison ? price.toFixed(2) + '%' : '$' + price.toFixed(2)}</span>
                                ${vol ? `<span class="text-blue-400 font-mono text-xs">V: ${(vol / 1e6).toFixed(1)}M</span>` : ''}
                            </div>
                        </div>
                    `;
                }
                
                const toolWidth = 160;
                const toolHeight = 60;
                let left = param.point.x + 20;
                if (left > chartContainerRef.current.clientWidth - toolWidth) {
                    left = param.point.x - toolWidth - 20;
                }
                
                tooltipRef.current.style.left = left + 'px';
                tooltipRef.current.style.top = param.point.y + 20 + 'px';
            }
        });

        // Add Markers
        if (!isComparison && markers && markers.length > 0) {
            const chartMarkers = markers.map(m => ({
                time: m.date,
                position: m.type === 'BUY' ? 'belowBar' : 'aboveBar',
                color: m.type === 'BUY' ? '#10b981' : '#ef4444',
                shape: m.type === 'BUY' ? 'arrowUp' : 'arrowDown',
                text: m.type
            }));
            createSeriesMarkers(mainSeries as any, chartMarkers as any);
        }

        chart.timeScale().fitContent();
        chartRef.current = chart;

        const handleResize = () => {
            if (chartContainerRef.current && chartRef.current) {
                chartRef.current.applyOptions({ width: chartContainerRef.current.clientWidth });
            }
        };

        window.addEventListener('resize', handleResize);

        return () => {
            window.removeEventListener('resize', handleResize);
            chart.remove();
        };
    }, [data, isFullscreen, comparisonData, tool]);

    return (
        <div className={`relative ${isFullscreen ? 'fixed inset-0 z-[200] bg-[#0f172a] p-4' : 'w-full h-full'}`}>
            <div ref={tooltipRef} className="absolute pointer-events-none p-3 bg-slate-900/90 backdrop-blur-md border border-slate-700 rounded-lg shadow-2xl z-[220] hidden min-w-[140px]" />
            <div className="absolute top-4 right-4 z-[210] flex gap-2">
                <div className="flex bg-[#1e293b]/80 backdrop-blur-md rounded-lg border border-slate-700 p-1">
                    <button 
                        onClick={() => setTool('pan')}
                        className={`p-1.5 rounded ${tool === 'pan' ? 'bg-blue-600 text-white' : 'text-slate-400 hover:text-white'}`}
                        title="Pan Tool"
                    >
                        <MousePointer2 size={18} />
                    </button>
                    <button 
                        onClick={() => setTool('line')}
                        className={`p-1.5 rounded ${tool === 'line' ? 'bg-blue-600 text-white' : 'text-slate-400 hover:text-white'}`}
                        title="Trendline Tool"
                    >
                        <Pencil size={18} />
                    </button>
                </div>
                <button 
                    onClick={() => setIsFullscreen(!isFullscreen)}
                    className="p-2 bg-[#1e293b]/80 backdrop-blur-md rounded-lg border border-slate-700 text-slate-400 hover:text-white"
                >
                    {isFullscreen ? <Minimize2 size={20} /> : <Maximize2 size={20} />}
                </button>
            </div>
            <div ref={chartContainerRef} className="w-full h-full rounded-xl overflow-hidden border border-slate-700 bg-[#1e293b]" />
        </div>
    );
};

export default StockChart;
